package eu.kanade.tachiyomi.network

import okhttp3.CookieJar
import okhttp3.OkHttpClient

abstract class NetworkHelper {
	abstract val client: OkHttpClient

	/**
	 * A client that bypasses Cloudflare protection, for use with CDN requests
	 * that don't need the Cloudflare interceptor.
	 *
	 * @since extensions-lib 1.5
	 */
	open val nonCloudflareClient: OkHttpClient
		get() = client

	/** Cookie jar shared with [client]. Used by extensions that manage session cookies. */
	abstract val cookieJar: CookieJar

	@Deprecated("The regular client handles Cloudflare by default")
	open val cloudflareClient: OkHttpClient
		get() = client

	abstract fun defaultUserAgentProvider(): String
}
