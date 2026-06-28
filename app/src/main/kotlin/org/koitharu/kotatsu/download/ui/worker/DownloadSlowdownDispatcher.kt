package org.koitharu.kotatsu.download.ui.worker

import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor() {
	private val timeMap = MutableObjectLongMap<MangaSource>()
	private val defaultDelay = 1_600L

	suspend fun delay(source: MangaSource) {
		// Mihon never applies an app-wide page delay: extensions opt into their own OkHttp
		// rateLimit() interceptors. Keeping Kotatsu's 1.6 s delay here made reader preloads and
		// five-way downloads wait even after the extension network client was unthrottled.
		if (source is MihonMangaSource) return

		val lastRequest = synchronized(timeMap) {
			val res = timeMap.getOrDefault(source, 0L)
			timeMap[source] = SystemClock.elapsedRealtime()
			res
		}
		if (lastRequest != 0L) {
			delay(lastRequest + defaultDelay - SystemClock.elapsedRealtime())
		}
	}
}
