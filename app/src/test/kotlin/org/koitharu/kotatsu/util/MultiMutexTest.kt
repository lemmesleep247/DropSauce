package org.koitharu.kotatsu.util

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.util.MultiMutex

class MultiMutexTest {

	@Test
	fun singleLock() = runTest {
		val mutex = MultiMutex<Int>()
		mutex.lock(1)
		mutex.lock(2)
		mutex.unlock(1)
		assert(mutex.size == 1)
		mutex.unlock(2)
		assert(mutex.isEmpty())
	}

	@Test
	fun doubleLock() = runTest {
		val mutex = MultiMutex<Int>()
		mutex.lock(1)
		val secondLock = launch {
			mutex.lock(1)
		}
		runCurrent()
		assertFalse(secondLock.isCompleted)
		assertEquals(1, mutex.size)
		mutex.unlock(1)
		runCurrent()
		assertTrue(secondLock.isCompleted)
		assertEquals(1, mutex.size)
		mutex.unlock(1)
		assertTrue(mutex.isEmpty())
	}

	@Test
	fun cancellation() = runTest {
		val mutex = MultiMutex<Int>()
		mutex.lock(1)
		val job = launch {
			try {
				mutex.lock(1)
			} finally {
				mutex.unlock(1)
			}
		}
		withTimeout(2000) {
			job.cancelAndJoin()
		}
	}
}
