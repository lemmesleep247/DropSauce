package org.koitharu.kotatsu.core.exceptions.resolve

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates automatic Cloudflare challenge solving by launching a hidden [CloudFlareActivity]
 * over the current foreground activity. Concurrent requests for the same source share one attempt.
 */
@Singleton
class CaptchaAutoResolveCoordinator @Inject constructor(
	@ApplicationContext private val context: Context,
	private val foregroundActivityHolder: ForegroundActivityHolder,
) {

	private val mutex = Mutex()
	private val inFlight = ConcurrentHashMap<MangaSource, CompletableDeferred<Boolean>>()
	private val pendingActivityResult = ConcurrentHashMap<MangaSource, CompletableDeferred<Boolean>>()
	private val recentSuccessAt = ConcurrentHashMap<MangaSource, Long>()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	fun notifyResolveResult(source: MangaSource, success: Boolean) {
		pendingActivityResult.remove(source)?.complete(success)
	}

	suspend fun resolveInBackground(source: MangaSource, exception: CloudFlareProtectedException): Boolean {
		inFlight[source]?.let { return it.await() }
		if (isInCooldown(source)) {
			return false
		}
		val deferred = mutex.withLock {
			inFlight[source]?.let { return@withLock it }
			if (isInCooldown(source)) {
				return@withLock CompletableDeferred(false)
			}
			CompletableDeferred<Boolean>().also { fresh ->
				inFlight[source] = fresh
				scope.launch {
					runResolve(source, exception, fresh)
				}
			}
		}
		return deferred.await()
	}

	private suspend fun runResolve(
		source: MangaSource,
		exception: CloudFlareProtectedException,
		deferred: CompletableDeferred<Boolean>,
	) {
		try {
			val result = launchAndAwait(source, exception)
			if (result) {
				recentSuccessAt[source] = System.currentTimeMillis()
			}
			deferred.complete(result)
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			deferred.complete(false)
		} finally {
			inFlight.remove(source)
			pendingActivityResult.remove(source)
		}
	}

	private suspend fun launchAndAwait(
		source: MangaSource,
		exception: CloudFlareProtectedException,
	): Boolean {
		if (source == UnknownMangaSource) {
			return false
		}
		val launcher = foregroundActivityHolder.current ?: return false
		val resultDeferred = CompletableDeferred<Boolean>()
		pendingActivityResult[source] = resultDeferred
		val intent = AppRouter.cloudFlareResolveIntent(context, exception, hidden = true).apply {
			putExtra(CloudFlareActivity.EXTRA_AUTO_RESOLVE, true)
		}
		launcher.startActivity(intent)
		// The activity has its own 45s timeout, but it may be killed without calling finish().
		// Without this outer timeout the deferred would never complete and the source
		// would be stuck un-resolvable until app restart.
		return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
			resultDeferred.await()
		} ?: run {
			pendingActivityResult.remove(source, resultDeferred)
			false
		}
	}

	private fun isInCooldown(source: MangaSource): Boolean {
		val lastSuccessAt = recentSuccessAt[source] ?: return false
		return System.currentTimeMillis() - lastSuccessAt < RECENT_SUCCESS_COOLDOWN_MS
	}

	private companion object {
		const val RECENT_SUCCESS_COOLDOWN_MS = 30_000L
		const val RESOLVE_TIMEOUT_MS = 60_000L
	}
}
