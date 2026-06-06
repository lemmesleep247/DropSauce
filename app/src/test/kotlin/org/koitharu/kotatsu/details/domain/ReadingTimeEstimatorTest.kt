package org.koitharu.kotatsu.details.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReadingTimeEstimatorTest {

	@Test
	fun `uses three minutes per chapter before ten completed chapters`() {
		val time = checkNotNull(
			ReadingTimeEstimator.estimate(
				remainingChapters = 3,
				totalDurationMillis = TimeUnit.MINUTES.toMillis(90),
				chaptersRead = 9,
				isContinue = false,
			),
		)

		assertEquals(0, time.hours)
		assertEquals(9, time.minutes)
	}

	@Test
	fun `uses learned average after ten completed chapters`() {
		val time = checkNotNull(
			ReadingTimeEstimator.estimate(
				remainingChapters = 3,
				totalDurationMillis = TimeUnit.MINUTES.toMillis(50),
				chaptersRead = 10,
				isContinue = true,
			),
		)

		assertEquals(0, time.hours)
		assertEquals(15, time.minutes)
	}

	@Test
	fun `remaining chapters include current chapter until completed`() {
		assertEquals(
			7,
			ReadingTimeEstimator.getRemainingChapters(
				totalChapters = 10,
				currentChapterIndex = 3,
				isCompleted = false,
			),
		)
		assertEquals(
			0,
			ReadingTimeEstimator.getRemainingChapters(
				totalChapters = 10,
				currentChapterIndex = 9,
				isCompleted = true,
			),
		)
		assertEquals(
			10,
			ReadingTimeEstimator.getRemainingChapters(
				totalChapters = 10,
				currentChapterIndex = -1,
				isCompleted = false,
			),
		)
	}

	@Test
	fun `does not create display time when no chapters remain`() {
		assertNull(
			ReadingTimeEstimator.estimate(
				remainingChapters = 0,
				totalDurationMillis = TimeUnit.MINUTES.toMillis(50),
				chaptersRead = 10,
				isContinue = true,
			),
		)
	}
}
