package org.koitharu.kotatsu.mihon.compat

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.network.UserAgents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A [CookieJar] that writes to both [primary] and [secondary] on every response, so that
 * extensions injecting [AndroidCookieJar] see the same cookies as our OkHttp client.
 * Request cookies are read only from [primary] to keep our CF/proxy logic unchanged.
 */
private class SyncCookieJar(
	private val primary: CookieJar,
	private val secondary: AndroidCookieJar,
) : CookieJar {
	override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
		primary.saveFromResponse(url, cookies)
		secondary.saveFromResponse(url, cookies)
	}

	override fun loadForRequest(url: HttpUrl): List<Cookie> {
		val primaryCookies = primary.loadForRequest(url)
		val secondaryCookies = secondary.loadForRequest(url)
		if (secondaryCookies.isEmpty()) return primaryCookies
		// Merge both stores so that WebView-set cookies (e.g. cf_clearance acquired during
		// a Cloudflare challenge) are visible to OkHttp requests even if they were never
		// written to MutableCookieJar. Primary wins for same-named cookies.
		val merged = LinkedHashMap<String, Cookie>()
		secondaryCookies.forEach { merged[it.name] = it }
		primaryCookies.forEach { merged[it.name] = it }
		return merged.values.toList()
	}
}

class KotoNetworkHelper(
	private val baseClient: OkHttpClient,
	private val mutableCookieJar: MutableCookieJar,
	private val webViewExecutor: WebViewExecutor? = null,
	private val androidCookieJar: AndroidCookieJar? = null,
	private val userAgentProvider: () -> String = { UserAgents.CHROME_MOBILE },
) : NetworkHelper() {

	/** Expose the cookie jar so extensions can read/write session cookies. */
	override val cookieJar: CookieJar get() = mutableCookieJar

	/** A client without the Cloudflare interceptor, suitable for CDN/static requests. */
	override val nonCloudflareClient: OkHttpClient get() = baseClient

	override val client: OkHttpClient = OkHttpClient.Builder().apply {
		connectTimeout(baseClient.connectTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
		readTimeout(baseClient.readTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
		writeTimeout(baseClient.writeTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
		// Use a bridge cookie jar so that every Set-Cookie response is mirrored to
		// AndroidCookieJar (Android's system CookieManager). Extensions that do
		// `injectLazy<AndroidCookieJar>()` then see the same cookies our OkHttp client uses.
		cookieJar(
			if (androidCookieJar != null) SyncCookieJar(baseClient.cookieJar, androidCookieJar)
			else baseClient.cookieJar
		)
		dns(baseClient.dns)
		cache(baseClient.cache)
		dispatcher(baseClient.dispatcher)
		connectionPool(baseClient.connectionPool)
		followRedirects(baseClient.followRedirects)
		followSslRedirects(baseClient.followSslRedirects)
		retryOnConnectionFailure(baseClient.retryOnConnectionFailure)
		proxy(baseClient.proxy)
		proxySelector(baseClient.proxySelector)
		proxyAuthenticator(baseClient.proxyAuthenticator)
		socketFactory(baseClient.socketFactory)
		hostnameVerifier(baseClient.hostnameVerifier)

		// Ensure every extension request carries a User-Agent when the source didn't set one,
		// using the same configurable default as Mihon. Added first so it runs outermost,
		// before Cloudflare detection sees the request.
		addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

		baseClient.interceptors.forEach { interceptor ->
			// Skip GZip (handled by OkHttp) and Kotatsu's CloudFlareInterceptor: the latter throws
			// CloudFlareBlockedException on any 403/503 block page, which aborts extensions (e.g.
			// Kagane) that deliberately request a Cloudflare-fronted page and ignore the result.
			// Cloudflare handling for extensions is done by the dedicated interceptor below instead.
			val name = interceptor.javaClass.simpleName
			if (name != "GZipInterceptor" && name != "CloudFlareInterceptor") {
				addInterceptor(interceptor)
			}
		}

		// Add a Mihon-specific fallback detector.
		addInterceptor { chain ->
			// Pass the request through unmodified — Mihon does not inject synthetic headers
			// (X-Requested-With, Sec-Fetch-*, etc.) onto extension requests, and doing so makes
			// API-based sources (e.g. Kagane) look bot-like and trip Cloudflare's WAF.
			val request = chain.request()
			val response = chain.proceed(request)
			val challengeUrl = request.toChallengeUrl()
			when (CloudFlareHelper.checkResponseForProtection(response)) {
				// Mihon only WebView-solves the CAPTCHA case ("not on geo block") and otherwise
				// passes the response through. Several extensions (e.g. Kagane) deliberately fetch
				// a Cloudflare-fronted page and ignore a block response; throwing here would abort
				// their flow even though the request would succeed in Mihon. So pass it through.
				CloudFlareHelper.PROTECTION_BLOCKED -> {
					android.util.Log.d(
						"MihonNetwork",
						"Cloudflare block page passed through for ${request.url} (matching Mihon)",
					)
					response
				}

				CloudFlareHelper.PROTECTION_CAPTCHA -> {
					val host = request.url.host.lowercase()
					val source = request.tag(MangaSource::class.java)

					// Attempt a headless WebView solve like Mihon — regardless of whether the
					// request carries a Kotatsu MangaSource tag. Extension-issued requests (e.g.
					// Kagane's integrity GET to its Cloudflare-fronted root) are untagged, yet the
					// challenge is still solvable and the resulting cf_clearance lands in the shared
					// cookie jar. Previously we skipped solving when the tag was missing and threw a
					// hard "blocked" error, which aborted those extensions even though Mihon coped.
					if (webViewExecutor != null) {
						val cfEx = CloudFlareProtectedException(
							url = challengeUrl,
							source = source,
							headers = request.headers,
						)
						val resolved = runCatching {
							runBlocking { webViewExecutor.tryResolveCaptcha(cfEx, 15000L) }
						}.getOrDefault(false)

						if (resolved) {
							android.util.Log.i("MihonNetwork", "WebView Cloudflare solve succeeded for host=$host")
							response.close()
							// Retry the original request now that the cookie jar has cf_clearance.
							return@addInterceptor chain.proceed(request)
						}
						android.util.Log.w("MihonNetwork", "WebView Cloudflare solve failed for host=$host")
					}

					val clearance = mutableCookieJar.loadForRequest(request.url)
						.firstOrNull { it.name == "cf_clearance" }
						?.value

					when {
						// Untagged extension request (no MangaSource): match Mihon and let the
						// extension handle the unsolved challenge response itself instead of
						// aborting with a hard block error. Extensions like Kagane fetch a
						// Cloudflare-fronted page and ignore the result.
						source == null -> {
							android.util.Log.d(
								"MihonNetwork",
								"Unsolved Cloudflare challenge passed through for ${request.url}",
							)
							response
						}

						shouldSkipInteractiveAction(host, clearance) -> {
							android.util.Log.w(
								"MihonNetwork",
								"Skip interactive action for host=$host: repeated challenge with same cf_clearance",
							)
							response.closeThrowing(
								CloudFlareBlockedException(url = challengeUrl, source = source),
							)
						}

						else -> response.closeThrowing(
							InteractiveActionRequiredException(
								source = source,
								url = challengeUrl,
								userAgent = request.header("User-Agent"),
								successCookieUrl = challengeUrl,
								successCookieName = "cf_clearance",
							),
						)
					}
				}

				else -> response
			}
		}

		// Also log the Mihon network flow
		addInterceptor { chain ->
			val req = chain.request()
			val response = chain.proceed(req)
			val cf = response.header("server")
			val status = response.code
			if (cf == "cloudflare" || status == 403 || status == 503) {
				android.util.Log.d("MihonNetwork", "Response: $status for ${req.url}")
			}
			response
		}

		baseClient.networkInterceptors.forEach(::addNetworkInterceptor)
	}.build()

	@Deprecated("The regular client handles Cloudflare by default")
	override val cloudflareClient: OkHttpClient
		get() = client

	override fun defaultUserAgentProvider(): String = userAgentProvider()

	private fun Response.closeThrowing(error: Throwable): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}

	private fun Request.toChallengeUrl(): String {
		val referer = header("Referer")?.toHttpUrlOrNull()
		if (referer != null && referer.host == url.host) {
			return referer.newBuilder()
				.query(null)
				.fragment(null)
				.build()
				.toString()
		}
		return url.newBuilder()
			.encodedPath("/")
			.query(null)
			.fragment(null)
			.build()
			.toString()
	}

	private fun shouldSkipInteractiveAction(host: String, clearance: String?): Boolean {
		if (clearance.isNullOrBlank()) return false
		val now = System.currentTimeMillis()
		val last = recentChallengeAttempts[host]
		if (last == null || now - last.timestampMs > INTERACTIVE_RETRY_WINDOW_MS || last.clearance != clearance) {
			recentChallengeAttempts[host] = ChallengeAttempt(
				clearance = clearance,
				timestampMs = now,
				count = 1,
			)
			return false
		}
		val nextCount = last.count + 1
		recentChallengeAttempts[host] = last.copy(
			timestampMs = now,
			count = nextCount,
		)
		return nextCount >= 2
	}

	private data class ChallengeAttempt(
		val clearance: String,
		val timestampMs: Long,
		val count: Int,
	)

	companion object {
		private const val INTERACTIVE_RETRY_WINDOW_MS = 10 * 60 * 1000L
		private val recentChallengeAttempts = ConcurrentHashMap<String, ChallengeAttempt>()
	}
}

@Singleton
class KotoInjektBridge @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val cookieJar: MutableCookieJar,
	private val webViewExecutor: WebViewExecutor,
	private val settings: AppSettings,
) {
	@Volatile
	private var initialized = false

	// Single AndroidCookieJar instance shared across Injekt and KotoNetworkHelper.
	// It wraps Android's system CookieManager so extensions and WebViews share one cookie store.
	private val androidCookieJar = AndroidCookieJar()

	@Synchronized
	fun initialize() {
		if (initialized) return
		val application = context.applicationContext as Application
		val networkHelper = KotoNetworkHelper(
			baseClient = httpClient,
			mutableCookieJar = cookieJar,
			webViewExecutor = webViewExecutor,
			androidCookieJar = androidCookieJar,
			// Use the same UA the Cloudflare-solving WebView uses (device default) unless the
			// user set an explicit override. Cloudflare binds cf_clearance to the UA that earned
			// it, so a mismatch between the WebView UA and request UA gets the request blocked.
			userAgentProvider = {
				settings.mihonUserAgentOverride
					?: webViewExecutor.defaultUserAgent
					?: AppSettings.DEFAULT_MIHON_USER_AGENT
			},
		)
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		val xml = XML {
			defaultPolicy {
				ignoreUnknownChildren()
			}
			autoPolymorphic = true
			xmlDeclMode = XmlDeclMode.Charset
			indent = 2
			xmlVersion = XmlVersion.XML10
		}
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(application)
				addSingletonFactory<Context> { context.applicationContext }
				addSingletonFactory<NetworkHelper> { networkHelper }
				addSingletonFactory<OkHttpClient> { httpClient }
				addSingletonFactory<CookieJar> { cookieJar }
				// AndroidCookieJar wraps Android's system CookieManager.  Extensions that do
				// `val cookieManager: AndroidCookieJar by injectLazy()` (e.g. for custom CF
				// interceptors) need this registration or they crash with a missing-binding error.
				addSingletonFactory<AndroidCookieJar> { androidCookieJar }
				addSingletonFactory<Json> { json }
				addSingletonFactory<StringFormat> { json }
				addSingletonFactory<SerialFormat> { json }
				// XML serialization (used by extensions that parse XML manga sites)
				addSingletonFactory<XML> { xml }
				// ProtoBuf serialization (used by extensions that use protobuf APIs)
				addSingletonFactory<ProtoBuf> { ProtoBuf }
				// JavaScript engine — uses QuickJS for synchronous, thread-safe JS evaluation.
				// Context and executor params are kept for Injekt/API compat but QuickJS ignores them.
				addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(context) }
			}
		})
		initialized = true
	}
}
