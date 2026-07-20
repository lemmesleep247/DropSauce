package org.koitharu.kotatsu.core.logs

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_ENTRIES = 10_000

// ponytail: 2MB cap; on overflow the session file is truncated and starts over (ring-buffer spirit).
private const val MAX_FILE_BYTES = 2L * 1024 * 1024

@Singleton
class AppLogger @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val logsDir: File
		get() = File(context.filesDir, "logs")
	private val sessionFile: File
		get() = File(logsDir, "session.log")
	private val previousSessionFile: File
		get() = File(logsDir, "previous_session.log")

	@Volatile
	var isEnabled: Boolean = false
		private set

	private val buffer = ArrayBlockingQueue<String>(MAX_ENTRIES)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val stateLock = Any()
	private var readerJob: Job? = null
	private var logcatProcess: Process? = null
	private var generation = 0

	/**
	 * Preserve the log file from the last run so it can be offered on this launch, then let a fresh
	 * session start. Call once at process start, before [setEnabled].
	 */
	fun rollSession() {
		synchronized(stateLock) {
			runCatching {
				val current = sessionFile
				if (current.exists() && current.length() > 0) {
					previousSessionFile.delete()
					if (!current.renameTo(previousSessionFile)) {
						current.copyTo(previousSessionFile, overwrite = true)
						current.delete()
					}
				} else {
					current.delete()
				}
			}
		}
	}

	fun hasPreviousSessionLog(): Boolean = previousSessionFile.let { it.exists() && it.length() > 0 }

	fun readPreviousSessionLog(): String = runCatching { previousSessionFile.readText() }.getOrDefault("")

	fun clearPreviousSessionLog() {
		runCatching { previousSessionFile.delete() }
	}

	fun setEnabled(enabled: Boolean) {
		synchronized(stateLock) {
			if (enabled == isEnabled) return
			isEnabled = enabled
			generation++
			if (enabled) {
				buffer.clear()
				startReadingLocked(generation)
			} else {
				stopReadingLocked()
			}
		}
	}

	suspend fun stopAndDrainToString(): String {
		val job = synchronized(stateLock) {
			if (isEnabled) {
				isEnabled = false
				generation++
			}
			stopReadingLocked()
		}
		job?.join()
		return drainToString()
	}

	private fun drainToString(): String {
		val lines = ArrayList<String>(buffer.size)
		buffer.drainTo(lines)
		return lines.joinToString("\n")
	}

	private fun startReadingLocked(readerGeneration: Int) {
		val job = scope.launch(start = CoroutineStart.LAZY) {
			var process: Process? = null
			var writer: BufferedWriter? = null
			var bytesWritten = 0L
			try {
				val pid = android.os.Process.myPid().toString()
				val startedProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "--pid", pid))
				process = startedProcess
				synchronized(stateLock) {
					if (!isEnabled || generation != readerGeneration) {
						startedProcess.destroy()
						return@launch
					}
					logcatProcess = startedProcess
				}
				writer = runCatching {
					logsDir.mkdirs()
					sessionFile.bufferedWriter()
				}.getOrNull()
				BufferedReader(InputStreamReader(startedProcess.inputStream)).use { reader ->
					while (isActive) {
						val line = reader.readLine() ?: break
						if (!buffer.offer(line)) {
							buffer.poll()
							buffer.offer(line)
						}
						writer?.let { w ->
							runCatching {
								if (bytesWritten >= MAX_FILE_BYTES) {
									w.flush()
									writer = sessionFile.bufferedWriter() // truncate & restart
									bytesWritten = 0L
								}
								val out = writer ?: w
								out.write(line)
								out.newLine()
								out.flush() // per-line flush so logs survive a crash
								bytesWritten += line.length + 1
							}
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e("AppLogger", "Failed to read logcat", e)
			} finally {
				runCatching { writer?.close() }
				process?.destroy()
				synchronized(stateLock) {
					if (generation == readerGeneration) {
						logcatProcess = null
						readerJob = null
					}
				}
			}
		}
		readerJob = job
		job.start()
	}

	private fun stopReadingLocked(): Job? {
		val job = readerJob
		readerJob = null
		job?.cancel()
		logcatProcess?.let { process ->
			runCatching { process.inputStream.close() }
			process.destroy()
		}
		logcatProcess = null
		return job
	}
}
