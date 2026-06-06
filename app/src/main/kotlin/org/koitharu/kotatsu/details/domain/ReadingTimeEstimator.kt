package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.details.data.ReadingTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

object ReadingTimeEstimator {

	const val MIN_CHAPTERS_FOR_PERSONAL_ESTIMATE = 10
	val DEFAULT_TIME_PER_CHAPTER_MILLIS: Long = TimeUnit.MINUTES.toMillis(3)

	fun getTimePerChapterMillis(totalDurationMillis: Long, chaptersRead: Int): Long {
		return if (chaptersRead >= MIN_CHAPTERS_FOR_PERSONAL_ESTIMATE && totalDurationMillis > 0L) {
			(totalDurationMillis.toDouble() / chaptersRead).roundToLong().coerceAtLeast(1L)
		} else {
			DEFAULT_TIME_PER_CHAPTER_MILLIS
		}
	}

	fun getRemainingChapters(totalChapters: Int, currentChapterIndex: Int, isCompleted: Boolean): Int = when {
		totalChapters <= 0 -> 0
		currentChapterIndex !in 0 until totalChapters -> totalChapters
		isCompleted -> 0
		else -> totalChapters - currentChapterIndex
	}

	fun estimate(
		remainingChapters: Int,
		totalDurationMillis: Long,
		chaptersRead: Int,
		isContinue: Boolean,
	): ReadingTime? {
		if (remainingChapters <= 0) {
			return null
		}
		val timePerChapterMillis = getTimePerChapterMillis(totalDurationMillis, chaptersRead)
		val estimatedTimeSec = TimeUnit.MILLISECONDS.toSeconds(timePerChapterMillis * remainingChapters)
		if (estimatedTimeSec < 60) {
			return null
		}
		return ReadingTime(
			minutes = ((estimatedTimeSec / 60) % 60).toInt(),
			hours = (estimatedTimeSec / 3600).toInt(),
			isContinue = isContinue,
		)
	}
}
