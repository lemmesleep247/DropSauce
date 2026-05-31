package org.koitharu.kotatsu.core.network

import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.net.InetAddress
import java.net.UnknownHostException

class DoHManager(
	cache: Cache,
	private val settings: AppSettings,
) : Dns {

	private val bootstrapClient = OkHttpClient.Builder().cache(cache).build()

	private var cachedDelegate: Dns? = null
	private var cachedProvider: DoHProvider? = null

	override fun lookup(hostname: String): List<InetAddress> {
		return try {
			getDelegate().lookup(hostname)
		} catch (e: UnknownHostException) {
			// fallback
			Dns.SYSTEM.lookup(hostname)
		}
	}

	@Synchronized
	private fun getDelegate(): Dns {
		var delegate = cachedDelegate
		val provider = settings.dnsOverHttps
		if (delegate == null || provider != cachedProvider) {
			delegate = createDelegate(provider)
			cachedDelegate = delegate
			cachedProvider = provider
		}
		return delegate
	}

	private fun createDelegate(provider: DoHProvider): Dns = when (provider) {
		DoHProvider.NONE -> Dns.SYSTEM
		DoHProvider.GOOGLE -> doh(
			url = "https://dns.google/dns-query",
			"8.8.4.4",
			"8.8.8.8",
			"2001:4860:4860::8888",
			"2001:4860:4860::8844",
		)

		DoHProvider.CLOUDFLARE -> doh(
			url = "https://cloudflare-dns.com/dns-query",
			"162.159.36.1",
			"162.159.46.1",
			"1.1.1.1",
			"1.0.0.1",
			"162.159.132.53",
			"2606:4700:4700::1111",
			"2606:4700:4700::1001",
			"2606:4700:4700::0064",
			"2606:4700:4700::6400",
		)

		DoHProvider.ADGUARD -> doh(
			url = "https://dns-unfiltered.adguard.com/dns-query",
			"94.140.14.140",
			"94.140.14.141",
			"2a10:50c0::1:ff",
			"2a10:50c0::2:ff",
		)

		DoHProvider.QUAD9 -> doh(
			url = "https://dns.quad9.net/dns-query",
			"9.9.9.9",
			"149.112.112.112",
			"2620:fe::fe",
			"2620:fe::9",
		)

		DoHProvider.ALIDNS -> doh(
			url = "https://dns.alidns.com/dns-query",
			"223.5.5.5",
			"223.6.6.6",
			"2400:3200::1",
			"2400:3200:baba::1",
		)

		DoHProvider.DNSPOD -> doh(
			url = "https://doh.pub/dns-query",
			"1.12.12.12",
			"120.53.53.53",
		)

		DoHProvider.THREE_SIXTY -> doh(
			url = "https://doh.360.cn/dns-query",
			"101.226.4.6",
			"218.30.118.6",
			"123.125.81.6",
			"140.207.198.6",
			"180.163.249.75",
			"101.199.113.208",
			"36.99.170.86",
		)

		DoHProvider.QUAD101 -> doh(
			url = "https://dns.twnic.tw/dns-query",
			"101.101.101.101",
			"2001:de4::101",
			"2001:de4::102",
		)

		DoHProvider.MULLVAD -> doh(
			url = "https://dns.mullvad.net/dns-query",
			"194.242.2.2",
			"2a07:e340::2",
		)

		DoHProvider.CONTROLD -> doh(
			url = "https://freedns.controld.com/p0",
			"76.76.2.0",
			"76.76.10.0",
			"2606:1a40::",
			"2606:1a40:1::",
		)

		DoHProvider.NJALLA -> doh(
			url = "https://dns.njal.la/dns-query",
			"95.215.19.53",
			"2001:67c:2354:2::53",
		)

		DoHProvider.SHECAN -> doh(
			url = "https://free.shecan.ir/dns-query",
			"178.22.122.100",
			"185.51.200.2",
		)

		DoHProvider.ZERO_MS -> DnsOverHttps.Builder().client(bootstrapClient)
			.url("https://v.recipes/dns-query".toHttpUrl())
			.resolvePublicAddresses(true)
			.build()
	}

	private fun doh(
		url: String,
		vararg bootstrapHosts: String,
	): Dns = DnsOverHttps.Builder().client(bootstrapClient)
		.url(url.toHttpUrl())
		.resolvePrivateAddresses(true)
		.bootstrapDnsHosts(bootstrapHosts.mapNotNull(::tryGetByIp))
		.build()

	private fun tryGetByIp(ip: String): InetAddress? = try {
		InetAddress.getByName(ip)
	} catch (e: UnknownHostException) {
		e.printStackTraceDebug()
		null
	}
}
