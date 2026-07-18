package org.koitharu.kotatsu.browser.cloudflare

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

private val BLOCKED_HEADERS = setOf(
	"sec-ch-ua",
	"sec-ch-ua-full-version-list",
	"x-requested-with",
)

/**
 * Replays main-frame requests via OkHttp with WebView-specific headers
 * (X-Requested-With, Client Hints) stripped, to avoid Turnstile blocks.
 */
class CloudFlareInterceptClient(
	cookieJar: MutableCookieJar,
	callback: CloudFlareActivity,
	adBlock: AdBlock,
	targetUrl: String,
	baseHttpClient: OkHttpClient,
) : CloudFlareClient(cookieJar, callback, adBlock = adBlock, targetUrl = targetUrl) {

	private val targetUri = runCatching { URI(targetUrl) }.getOrNull()

	// Derived from the base client to keep proxy/DoH/SSL config,
	// minus CloudFlareInterceptor which would throw on the challenge page we need to display
	private val httpClient = baseHttpClient.newBuilder().apply {
		interceptors().removeAll { it is CloudFlareInterceptor }
		connectTimeout(15, TimeUnit.SECONDS)
		readTimeout(15, TimeUnit.SECONDS)
		callTimeout(30, TimeUnit.SECONDS)
	}.build()

	override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
		if (request == null || !shouldReplayRequest(request)) {
			return super.shouldInterceptRequest(view, request)
		}
		return runCatching {
			replayRequest(request)
		}.getOrNull()
	}

	private fun replayRequest(request: WebResourceRequest): WebResourceResponse? {
		val builder = Request.Builder()
			.url(request.url.toString())
			.method(request.method, null)
		for ((key, value) in request.requestHeaders) {
			val lower = key.lowercase(Locale.ROOT)
			// Also strip Accept-Encoding: WebView does not decode Content-Encoding of
			// intercepted responses, so let OkHttp handle gzip transparently instead
			if (lower !in BLOCKED_HEADERS && lower != "accept-encoding") {
				builder.addHeader(key, value)
			}
		}
		val response = httpClient.newCall(builder.build()).execute()
		return try {
			if (response.code in 300..399) {
				// WebResourceResponse forbids 3xx (e.g. a 304 from replayed conditional headers);
				// let WebView handle the request itself
				response.close()
				return null
			}
			val contentType = response.header("Content-Type")
			val mimeType: String
			val charset: String?
			if (contentType != null) {
				val parts = contentType.split(";")
				mimeType = parts[0].trim()
				charset = parts.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
					?.substringAfter("=")
					?.trim()
			} else {
				mimeType = "text/html"
				charset = "UTF-8"
			}
			WebResourceResponse(
				mimeType,
				charset,
				response.code,
				response.message.ifBlank { "OK" },
				buildMap { response.headers.forEach { put(it.first, it.second) } },
				response.body.byteStream(), // closing the stream (WebView does) closes the response
			)
		} catch (e: Throwable) {
			response.close()
			throw e
		}
	}

	private fun shouldReplayRequest(request: WebResourceRequest): Boolean {
		if (request.method != "GET" || !request.isForMainFrame) {
			return false
		}
		if (request.requestHeaders.keys.none { it.lowercase(Locale.ROOT) in BLOCKED_HEADERS }) {
			return false
		}
		val requestUri = runCatching { URI(request.url.toString()) }.getOrNull() ?: return false
		return requestUri.scheme.equals(targetUri?.scheme, ignoreCase = true) &&
			requestUri.host.equals(targetUri?.host, ignoreCase = true) &&
			normalizedPort(requestUri) == normalizedPort(targetUri)
	}

	private fun normalizedPort(uri: URI?): Int {
		if (uri == null) {
			return -1
		}
		return when {
			uri.port != -1 -> uri.port
			uri.scheme.equals("https", ignoreCase = true) -> 443
			uri.scheme.equals("http", ignoreCase = true) -> 80
			else -> -1
		}
	}
}
