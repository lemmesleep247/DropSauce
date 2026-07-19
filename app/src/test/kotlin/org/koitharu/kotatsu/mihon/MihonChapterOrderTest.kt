package org.koitharu.kotatsu.mihon

import org.junit.Assert.assertEquals
import org.junit.Test

class MihonChapterOrderTest {

	@Test
	fun `newest-first Mihon chapters become oldest-first for tracker`() {
		val sourceOrder = listOf("A-11", "B-10", "A-10", "B-9")

		val result = normalizeMihonChapterOrder(sourceOrder)

		assertEquals(listOf("B-9", "A-10", "B-10", "A-11"), result)
		assertEquals(listOf("A-10", "A-11"), result.filter { it.startsWith("A-") })
		assertEquals(listOf("B-9", "B-10"), result.filter { it.startsWith("B-") })
	}

	@Test
	fun `chapter path follows a changed canonical manga path`() {
		assertEquals(
			"/title/dkw-one-piece/9054304-chapter-1188-en",
			normalizeChapterUrl(
				chapterUrl = "/manga/one-piecee.dkw/9054304-chapter-1188-en",
				oldMangaUrl = "/manga/one-piecee.dkw",
				newMangaUrl = "/title/dkw-one-piece",
			),
		)
	}
}
