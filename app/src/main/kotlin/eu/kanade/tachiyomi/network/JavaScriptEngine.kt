package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import kotlin.coroutines.resume

/**
 * Executes JavaScript in a sandboxed WebView.
 *
 * When [executor] is provided (the normal runtime path via Injekt), all execution is
 * delegated to [WebViewExecutor] so that proxy settings, WebView reuse, and proper
 * setup are all applied automatically.
 *
 * The fallback standalone path is used only if constructed without an executor (e.g.
 * in tests or direct instantiation). It does not apply proxy settings.
 *
 * Return value mirrors Android's [WebView.evaluateJavascript] semantics:
 * strings are JSON-quoted ("\"hello\""), numbers are plain ("42"),
 * null/undefined returns "null".
 *
 * @since extension-lib 1.4
 */
class JavaScriptEngine(
	private val context: Context,
	private val executor: WebViewExecutor? = null,
) {

	@Suppress("UNCHECKED_CAST")
	suspend fun <T> evaluate(script: String): T {
		if (executor != null) {
			// Delegate to WebViewExecutor: proxy-aware, reuses a cached WebView,
			// and applies configureForParser() setup.
			// evaluateJs returns null for JS null/undefined; map it to the string "null"
			// to match raw evaluateJavascript() semantics that extensions expect.
			return (executor.evaluateJs(null, script) ?: "null") as T
		}
		// Standalone fallback — no proxy support.
		return withContext(Dispatchers.Main) {
			val webView = WebView(context.applicationContext)
			webView.settings.javaScriptEnabled = true
			try {
				withTimeout(10_000L) {
					suspendCancellableCoroutine { cont ->
						webView.webViewClient = object : WebViewClient() {
							override fun onPageFinished(view: WebView, url: String) {
								view.webViewClient = WebViewClient()
								if (cont.isActive) cont.resume(Unit)
							}
						}
						webView.loadUrl("about:blank")
					}
					suspendCancellableCoroutine { cont ->
						webView.evaluateJavascript(script) { result ->
							if (cont.isActive) cont.resume(result as T)
						}
					}
				}
			} finally {
				webView.destroy()
			}
		}
	}
}
