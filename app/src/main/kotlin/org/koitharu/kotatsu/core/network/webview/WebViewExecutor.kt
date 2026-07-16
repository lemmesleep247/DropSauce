package org.koitharu.kotatsu.core.network.webview

import android.content.Context
import android.util.AndroidRuntimeException
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	val defaultUserAgent: String? by lazy {
		try {
			WebSettings.getDefaultUserAgent(context)
		} catch (e: AndroidRuntimeException) {
			e.printStackTraceDebug()
			// Probably WebView is not available
			null
		}
	}
}
