package org.koitharu.kotatsu.download.ui.worker

import android.content.Context
import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val timeMap = MutableObjectLongMap<MangaSource>()
	private val settingsMap = HashMap<MangaSource, SourceSettings>()
	private val defaultDelay = 1_600L

	suspend fun delay(source: MangaSource) {
		val settings = synchronized(settingsMap) {
			settingsMap.getOrPut(source) { SourceSettings(context, source) }
		}
		if (!settings.isSlowdownEnabled) {
			return
		}
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
