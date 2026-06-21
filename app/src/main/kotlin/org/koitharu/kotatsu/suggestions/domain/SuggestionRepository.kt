package org.koitharu.kotatsu.suggestions.domain

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTagsList
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.suggestions.data.SuggestionEntity
import org.koitharu.kotatsu.suggestions.data.SuggestionWithManga
import javax.inject.Inject

class SuggestionRepository @Inject constructor(
	private val db: MangaDatabase,
) {

	fun observeAll(): Flow<List<Manga>> {
		return db.getSuggestionDao().observeAll().mapItems {
			it.toManga()
		}
	}

	fun observeAll(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<Manga>> {
		return db.getSuggestionDao().observeAll(limit, filterOptions).mapItems {
			it.toManga()
		}
	}

	suspend fun getRandomList(limit: Int): List<Manga> {
		return db.getSuggestionDao().getRandom(limit).map {
			it.toManga()
		}
	}

	/**
	 * Reactive variant of [getRandomList]: emits a fresh random subset whenever the set of stored
	 * suggestions changes (e.g. after a manual refresh), so observers like the Explore carousel stay
	 * in sync instead of being frozen until the next app start. Re-shuffles only when the suggestion
	 * membership actually changes, not on unrelated row updates.
	 */
	fun observeRandomList(limit: Int): Flow<List<Manga>> {
		return observeAll()
			.distinctUntilChangedBy { list -> list.mapTo(HashSet(list.size)) { it.id } }
			.map { it.shuffled().take(limit) }
	}

	suspend fun clear() {
		db.getSuggestionDao().deleteAll()
	}

	suspend fun isEmpty(): Boolean {
		return db.getSuggestionDao().count() == 0
	}

	suspend fun getTopTags(limit: Int): List<MangaTag> {
		return db.getSuggestionDao().getTopTags(limit)
			.toMangaTagsList()
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		return db.getSuggestionDao().getTopSources(limit)
			.toMangaSources()
	}

	suspend fun replace(suggestions: Iterable<MangaSuggestion>) {
		db.withTransaction {
			db.getSuggestionDao().deleteAll()
			suggestions.forEach { (manga, relevance) ->
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				db.getSuggestionDao().upsert(
					SuggestionEntity(
						mangaId = manga.id,
						relevance = relevance,
						createdAt = System.currentTimeMillis(),
					),
				)
			}
		}
	}

	private fun SuggestionWithManga.toManga() = manga.toManga(emptySet(), null)
}
