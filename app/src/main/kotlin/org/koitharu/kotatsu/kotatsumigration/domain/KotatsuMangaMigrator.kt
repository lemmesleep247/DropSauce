package org.koitharu.kotatsu.kotatsumigration.domain

import androidx.room.withTransaction
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import javax.inject.Inject

/**
 * Re-keys a restored Kotatsu library entry onto its mapped Mihon source **offline** — no network.
 *
 * Because a Mihon manga's id is a pure hash of (source name, url) ([mihonMangaId]), the canonical
 * new id is computable without fetching. We store the same manga under that new id with the Mihon
 * source, move all user data (favourites, history with **percent preserved**, bookmarks, tracker,
 * scrobbling, stats) onto it, keep the cached chapters (so "continue reading" still resolves), and
 * delete the old row — its remaining children fall away via `ON DELETE CASCADE`.
 *
 * Manga details (live chapter list) load lazily the first time the user opens the manga, exactly
 * like any other manga. If the Kotatsu url happens to differ from the extension's url scheme, the
 * open-time `getDetails` 404s and the app's existing [org.koitharu.kotatsu.explore.domain.RecoverMangaUseCase]
 * repairs the url by title-search — keeping the same id, so favourites/history stay attached.
 */
class KotatsuMangaMigrator @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
) {

	/**
	 * @param newSource the target Mihon source: the running [org.koitharu.kotatsu.mihon.model.MihonMangaSource]
	 *  when its extension is installed, otherwise a `MissingMangaSource("MIHON_<id>", title)` so the
	 *  entry still converts and shows its cached title until the extension is installed.
	 * @return the new manga id, or null if nothing changed (already migrated).
	 */
	suspend operator fun invoke(oldManga: Manga, newSource: MangaSource): Long? {
		val oldId = oldManga.id
		val newId = mihonMangaId(newSource.name, oldManga.url)
		if (newId == oldId) {
			return null
		}
		// Store the manga under its new identity (source + id), keeping cached chapters so the
		// reader can resume offline until the first live refresh.
		val newManga = oldManga.copy(id = newId, source = newSource)
		mangaDataRepository.storeManga(newManga, replaceExisting = true)

		database.withTransaction {
			// favourites — copy onto the new id (old rows fall away with the old manga via CASCADE)
			val favouritesDao = database.getFavouritesDao()
			for (f in favouritesDao.findAllRaw(oldId)) {
				favouritesDao.upsert(f.copy(mangaId = newId))
			}

			// history — percent + chapter pointer preserved (cached chapters carry the same ids)
			val historyDao = database.getHistoryDao()
			historyDao.find(oldId)?.let { h ->
				historyDao.upsert(
					HistoryEntity(
						mangaId = newId,
						createdAt = h.createdAt,
						updatedAt = h.updatedAt,
						chapterId = h.chapterId,
						page = h.page,
						scroll = h.scroll,
						percent = h.percent,
						deletedAt = 0L,
						chaptersCount = h.chaptersCount,
					),
				)
			}

			// stats — FK -> history(manga_id), so the new history above must already exist
			val statsDao = database.getStatsDao()
			for (s in statsDao.findAll(oldId)) {
				statsDao.upsert(s.copy(mangaId = newId))
			}

			// tracker
			val tracksDao = database.getTracksDao()
			tracksDao.find(oldId)?.let { t ->
				tracksDao.upsert(
					TrackEntity(
						mangaId = newId,
						lastChapterId = t.lastChapterId,
						newChapters = t.newChapters,
						lastCheckTime = t.lastCheckTime,
						lastChapterDate = t.lastChapterDate,
						lastResult = t.lastResult,
						lastError = t.lastError,
					),
				)
			}

			// bookmarks — chapter pointer kept (cached chapters carry the same ids)
			val bookmarksDao = database.getBookmarksDao()
			val oldBookmarks = bookmarksDao.findAll(oldId)
			if (oldBookmarks.isNotEmpty()) {
				bookmarksDao.upsert(oldBookmarks.map { it.copy(mangaId = newId) })
			}

			// scrobbling — no manga FK, so move explicitly and delete the old rows
			val scrobblingDao = database.getScrobblingDao()
			val oldScrobblings = scrobblingDao.findAll(oldId)
			for (s in oldScrobblings) {
				scrobblingDao.upsert(
					ScrobblingEntity(
						scrobbler = s.scrobbler,
						id = s.id,
						mangaId = newId,
						targetId = s.targetId,
						status = s.status,
						chapter = s.chapter,
						comment = s.comment,
						rating = s.rating,
					),
				)
			}
			for (scrobbler in oldScrobblings.map { it.scrobbler }.distinct()) {
				scrobblingDao.delete(scrobbler, oldId)
			}

			// retire the old entry — CASCADE removes its favourites/history/bookmarks/tracks/stats/
			// chapters/tags that still reference the old id.
			database.getMangaDao().find(oldId)?.manga?.let { oldEntity ->
				database.getMangaDao().delete(listOf(oldEntity))
			}
		}
		return newId
	}
}
