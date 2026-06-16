package org.koitharu.kotatsu.list.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.NONE
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.PERCENT_READ

class ReadingProgressTest {

	@Test
	fun `percent mode accepts only normalized progress`() {
		assertTrue(readingProgress(percent = 0.4f, mode = PERCENT_READ).isValid())
		assertFalse(readingProgress(percent = 1.1f, mode = PERCENT_READ).isValid())
	}

	@Test
	fun `none mode disables progress`() {
		assertFalse(readingProgress(percent = 0.4f, mode = NONE).isValid())
	}

	@Test
	fun `percent strings are clamped to whole percentages`() {
		assertEquals("0", ReadingProgress.percentToString(ReadingProgress.PROGRESS_NONE))
		assertEquals("40", ReadingProgress.percentToString(0.4f))
		assertEquals("100", ReadingProgress.percentToString(ReadingProgress.PROGRESS_COMPLETED))
	}

	private fun readingProgress(
		percent: Float,
		mode: ProgressIndicatorMode,
	) = ReadingProgress(
		percent = percent,
		totalChapters = 10,
		mode = mode,
	)
}
