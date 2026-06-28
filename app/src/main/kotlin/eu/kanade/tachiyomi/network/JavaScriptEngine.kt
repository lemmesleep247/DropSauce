package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Util for evaluating JavaScript in sources.
 *
 * @since extension-lib 1.4
 */
@Suppress("UNUSED")
class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

	@Suppress("UNCHECKED_CAST")
	suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.IO) {
		QuickJs.create().use {
			it.evaluate(script) as T
		}
	}
}
