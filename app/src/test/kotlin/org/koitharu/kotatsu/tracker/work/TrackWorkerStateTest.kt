package org.koitharu.kotatsu.tracker.work

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackWorkerStateTest {

	@Test
	fun `queued manual check is active`() {
		assertTrue(isTrackerWorkActive(WorkInfo.State.ENQUEUED, setOf("tracking_oneshot")))
	}

	@Test
	fun `queued periodic check is not active`() {
		assertFalse(isTrackerWorkActive(WorkInfo.State.ENQUEUED, setOf("tracking")))
	}

	@Test
	fun `running periodic check is active`() {
		assertTrue(isTrackerWorkActive(WorkInfo.State.RUNNING, setOf("tracking")))
	}

	@Test
	fun `finished manual check is inactive`() {
		assertFalse(isTrackerWorkActive(WorkInfo.State.SUCCEEDED, setOf("tracking_oneshot")))
	}
}
