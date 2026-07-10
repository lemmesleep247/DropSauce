package org.koitharu.kotatsu.tracker.domain

import android.util.Log
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.withMergedBranches
import org.koitharu.kotatsu.core.parser.FreshMangaDetailsRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckNewChaptersUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
	private val settings: AppSettings,
	private val mangaDataRepository: MangaDataRepository,
) {

	private val mutex = MultiMutex<Long>()

	suspend operator fun invoke(manga: Manga): MangaUpdates = mutex.withLock(manga.id) {
		repository.updateTracks()
		val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
			manga = manga,
			error = null,
		)
		invokeImpl(tracking)
	}

	suspend operator fun invoke(track: MangaTracking): MangaUpdates = mutex.withLock(track.manga.id) {
		invokeImpl(track)
	}

	suspend operator fun invoke(manga: Manga, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullManga(manga).let {
				if (mangaDataRepository.isScanlatorsMerged(manga.id)) it.withMergedBranches() else it
			}
			val track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch)
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastNewChapterIndex = chapters.size - track.newChapters
			val lastChapter = chapters.lastOrNull()
			val tracking = MangaTracking(
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
				newChapters = when {
					track.newChapters == 0 -> 0
					chapterIndex < 0 -> track.newChapters
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> track.newChapters
				},
			)
			repository.mergeWith(tracking)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun invokeImpl(track: MangaTracking): MangaUpdates = runCatchingCancellable {
		val isMerged = mangaDataRepository.isScanlatorsMerged(track.manga.id)
		val cachedChapters = mangaDataRepository.findMangaById(track.manga.id, withChapters = true)
			?.chapters
			.orEmpty()
		val details = getFullManga(track.manga).let { if (isMerged) it.withMergedBranches() else it }
		val branch = if (isMerged) null else getBranch(details, track.lastChapterId)
		compare(
			track = track,
			manga = details,
			branch = branch,
			cachedChapterIds = cachedChapters.asSequence()
				.filter { isMerged || it.branch == branch }
				.mapTo(HashSet()) { it.id },
		)
	}.getOrElse { error ->
		MangaUpdates.Failure(
			manga = track.manga,
			error = error,
		)
	}.also { updates ->
		repository.saveUpdates(updates)
	}

	private suspend fun getBranch(manga: Manga, trackChapterId: Long): String? {
		historyRepository.getOne(manga)?.let {
			manga.chapters?.findById(it.chapterId)
		}?.let {
			return it.branch
		}
		manga.chapters?.findById(trackChapterId)?.let {
			return it.branch
		}
		// fallback
		return manga.getPreferredBranch(null)
	}

	private suspend fun getFullManga(manga: Manga): Manga = when {
		manga.isLocal -> fetchDetails(
			requireNotNull(localMangaRepository.getRemoteManga(manga)) {
				"Local manga is not supported"
			},
		)

		manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
		else -> manga
	}

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		val details = if (repo is FreshMangaDetailsRepository) {
			repo.getFreshDetails(manga)
		} else {
			repo.getDetails(manga)
		}
		// Persist so opening the manga later shows cached details instead of a cold load
		mangaDataRepository.storeManga(details, replaceExisting = true, stripAppliedOverride = false, detailsFetched = true)
		return details
	}

	private fun compare(
		track: MangaTracking,
		manga: Manga,
		branch: String?,
		cachedChapterIds: Set<Long>,
	): MangaUpdates.Success {
		val chapters = requireNotNull(manga.getChapters(branch))
		val installTime = settings.onboardingInstallTime
		val lastCheckTime = track.lastCheck?.toEpochMilli() ?: installTime

		if (track.isEmpty()) {
			val newChapters = chapters.findFallbackChapters(cachedChapterIds, lastCheckTime)
			return MangaUpdates.Success(
				manga = manga,
				branch = branch,
				newChapters = newChapters,
				isValid = newChapters.isNotEmpty(),
			)
		}
		if (BuildConfig.DEBUG && chapters.findById(track.lastChapterId) == null) {
			Log.e("Tracker", "Chapter ${track.lastChapterId} not found")
		}
		val newChapters = chapters.takeLastWhile { x -> x.id != track.lastChapterId }
		return when {
			newChapters.isEmpty() -> {
				MangaUpdates.Success(
					manga = manga,
					branch = branch,
					newChapters = emptyList(),
					isValid = chapters.lastOrNull()?.id == track.lastChapterId,
				)
			}

			newChapters.size == chapters.size -> {
				val fallbackChapters = chapters.findFallbackChapters(cachedChapterIds, lastCheckTime)
				MangaUpdates.Success(manga, branch, fallbackChapters, isValid = fallbackChapters.isNotEmpty())
			}

			else -> {
				MangaUpdates.Success(manga, branch, newChapters, isValid = true)
			}
		}
	}
}

private fun List<MangaChapter>.findFallbackChapters(
	cachedChapterIds: Set<Long>,
	lastCheckTime: Long,
): List<MangaChapter> {
	val fallbackIds = findFallbackChapterIds(
		currentChapters = map { ChapterFingerprint(it.id, it.uploadDate) },
		cachedChapterIds = cachedChapterIds,
		lastCheckTime = lastCheckTime,
	)
	return filter { it.id in fallbackIds }
}

internal data class ChapterFingerprint(
	val id: Long,
	val uploadDate: Long,
)

internal fun findFallbackChapterIds(
	currentChapters: List<ChapterFingerprint>,
	cachedChapterIds: Set<Long>,
	lastCheckTime: Long,
): Set<Long> {
	val datesAreAmbiguous = currentChapters.any { it.uploadDate <= 0L } ||
		(currentChapters.size > 1 && currentChapters.all { it.uploadDate == currentChapters.first().uploadDate })
	val cacheHasOverlap = cachedChapterIds.isNotEmpty() &&
		currentChapters.any { it.id in cachedChapterIds }
	if (datesAreAmbiguous && cacheHasOverlap) {
		return currentChapters
			.asSequence()
			.filterNot { it.id in cachedChapterIds }
			.mapTo(HashSet()) { it.id }
	}
	if (lastCheckTime <= 0L) {
		return emptySet()
	}
	return currentChapters
		.asSequence()
		.filter { it.uploadDate > lastCheckTime }
		.mapTo(HashSet()) { it.id }
}
