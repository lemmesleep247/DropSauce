package org.koitharu.kotatsu.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CheckNewChaptersFallbackTest {

	@Test
	fun `missing dates use cached chapter ids`() {
		val current = chapters(
			1L to 0L,
			2L to 0L,
			3L to 0L,
		)

		val result = findFallbackChapterIds(current, setOf(1L, 2L), lastCheckTime = 100L)

		assertEquals(setOf(3L), result)
	}

	@Test
	fun `identical dates use cached chapter ids`() {
		val current = chapters(
			1L to 50L,
			2L to 50L,
			3L to 50L,
		)

		val result = findFallbackChapterIds(current, setOf(1L, 2L), lastCheckTime = 100L)

		assertEquals(setOf(3L), result)
	}

	@Test
	fun `reliable dates retain existing date fallback`() {
		val current = chapters(
			1L to 50L,
			2L to 100L,
			3L to 150L,
		)

		val result = findFallbackChapterIds(current, setOf(1L), lastCheckTime = 125L)

		assertEquals(setOf(3L), result)
	}

	@Test
	fun `unrelated cache retains existing date fallback`() {
		val current = chapters(
			1L to 50L,
			2L to 50L,
			3L to 50L,
		)

		val result = findFallbackChapterIds(current, setOf(10L, 11L), lastCheckTime = 25L)

		assertEquals(setOf(1L, 2L, 3L), result)
	}

	private fun chapters(vararg values: Pair<Long, Long>) = values.map { (id, date) ->
		ChapterFingerprint(id = id, uploadDate = date)
	}
}
