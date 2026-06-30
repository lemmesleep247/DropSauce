package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot

class SyncMergerTest {

	@Test
	fun `deletion safety keeps the live remote favorite`() {
		val localDeletion = favourite(deletedAt = 300L)
		val remoteLive = favourite(deletedAt = 0L)

		val result = SyncMerger.mergeFavourites(
			local = listOf(localDeletion),
			remote = listOf(remoteLive),
			propagateDeletions = false,
		)

		assertEquals(1, result.size)
		assertSame(remoteLive, result.single())
	}

	@Test
	fun `deletions still propagate when safety is disabled`() {
		val localDeletion = favourite(deletedAt = 300L)
		val remoteLive = favourite(deletedAt = 0L)

		val result = SyncMerger.mergeFavourites(
			local = listOf(localDeletion),
			remote = listOf(remoteLive),
			propagateDeletions = true,
		)

		assertEquals(1, result.size)
		assertSame(localDeletion, result.single())
	}

	@Test
	fun `deletion safety removes category tombstones from the cloud result`() {
		val deletion = category(deletedAt = 300L)

		val result = SyncMerger.mergeCategories(
			local = listOf(deletion),
			remote = emptyList(),
			propagateDeletions = false,
		)

		assertEquals(emptyList<SyncCategory>(), result)
	}

	@Test
	fun `same feed event detected on two devices is merged once`() {
		val local = feed(chapters = "Chapter 11\nChapter 12", createdAt = 200L, unread = true)
		val remote = feed(chapters = " chapter 12 \nCHAPTER 11", createdAt = 100L, unread = false)

		val result = SyncMerger.mergeFeed(listOf(local), listOf(remote))

		assertEquals(1, result.size)
		assertEquals(100L, result.single().createdAt)
		assertFalse(result.single().isUnread)
	}

	@Test
	fun `different feed chapter events remain separate`() {
		val result = SyncMerger.mergeFeed(
			local = listOf(feed(chapters = "Chapter 11")),
			remote = listOf(feed(chapters = "Chapter 12")),
		)

		assertEquals(2, result.size)
	}

	@Test
	fun `schema one snapshots remain readable`() {
		val snapshot = Json.decodeFromString<SyncSnapshot>("""{"schema":1}""")
		val prefs = Json.decodeFromString<SyncMangaPrefs>(
			"""
			{
				"manga_id": 1,
				"mode": 0,
				"cf_brightness": 0.0,
				"cf_contrast": 0.0,
				"cf_invert": false,
				"cf_grayscale": false,
				"cf_book": false,
				"cover_override": "file:///old-device/cover.jpg"
			}
			""".trimIndent(),
		)

		assertEquals(1, snapshot.schemaVersion)
		assertEquals(emptyList<SyncFeedEntry>(), snapshot.feed)
		assertNull(prefs.coverData)
	}

	private fun favourite(deletedAt: Long) = SyncFavourite(
		mangaId = MANGA_ID,
		categoryId = 2L,
		sortKey = 0,
		isPinned = false,
		createdAt = 100L,
		deletedAt = deletedAt,
		manga = manga(),
	)

	private fun category(deletedAt: Long) = SyncCategory(
		categoryId = 2,
		createdAt = 100L,
		sortKey = 0,
		title = "Reading",
		order = "NEWEST",
		track = true,
		downloadNewChapters = false,
		isVisibleInLibrary = true,
		deletedAt = deletedAt,
	)

	private fun feed(
		chapters: String,
		createdAt: Long = 100L,
		unread: Boolean = true,
	) = SyncFeedEntry(
		mangaId = MANGA_ID,
		chapters = chapters,
		createdAt = createdAt,
		isUnread = unread,
		manga = manga(),
	)

	private fun manga() = MangaBackup(
		id = MANGA_ID,
		title = "Manga",
		url = "/manga",
		publicUrl = "https://example.com/manga",
		coverUrl = "https://example.com/cover.jpg",
		source = "TEST",
	)

	private companion object {

		const val MANGA_ID = 1L
	}
}
