package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor

/**
 * Util for evaluating JavaScript in sources.
 *
 * The unused [context] and [executor] parameters are kept for compatibility with
 * the existing Injekt bridge constructor.
 *
 * @since extension-lib 1.4
 */
@Suppress("UNUSED")
class JavaScriptEngine(
	@Suppress("UNUSED_PARAMETER") context: Context,
	@Suppress("UNUSED_PARAMETER") executor: WebViewExecutor? = null,
) {

	@Suppress("UNCHECKED_CAST")
	suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.IO) {
		QuickJs.create().use {
			it.evaluate(script) as T
		}
	}
}
