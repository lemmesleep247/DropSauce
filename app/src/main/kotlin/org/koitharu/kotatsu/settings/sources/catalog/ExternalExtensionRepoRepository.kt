package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.ExternalRepoInfo
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalExtensionRepoRepository @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
	}
	private val protoBuf = ProtoBuf { }

	/**
	 * Loads a repo's extension list, transparently supporting both the legacy `index.min.json` array
	 * and the newer "extension store" index (a JSON or protobuf `index.pb`, optionally gzip-compressed,
	 * optionally with its list in a separate `extensionListUrl`). Everything is mapped back onto
	 * [ExternalExtensionRepoEntry] so callers don't care which format the repo uses.
	 */
	suspend fun getExtensions(repoUrl: String, forceRefresh: Boolean = false): List<ExternalExtensionRepoEntry> =
		withContext(Dispatchers.IO) {
			loadEntries(buildIndexUrl(repoUrl), forceRefresh, depth = 0)
				.filterNot { it.lang in MihonExtensionLoader.HIDDEN_LANGUAGES }
		}

	private fun loadEntries(url: String, forceRefresh: Boolean, depth: Int): List<ExternalExtensionRepoEntry> {
		if (depth > MAX_INDEX_HOPS) return emptyList() // guard against index_v2 / list-url cycles
		val bytes = fetchBytes(url, forceRefresh) ?: return emptyList()
		return when (bytes.firstOrNull()) {
			OPEN_BRACKET -> json.decodeFromString<List<ExternalExtensionRepoEntry>>(bytes.decodeToString())
			OPEN_BRACE -> {
				val text = bytes.decodeToString()
				val repoJson = runCatching { json.decodeFromString<ExternalRepoJson>(text) }.getOrNull()
				// A '{' body is either a repo.json (meta / index_v2 pointer) or a store object.
				if (repoJson != null && (repoJson.indexV2 != null || repoJson.meta.signingKeyFingerprint.isNotBlank())) {
					loadEntries(repoJson.indexV2 ?: "${getBaseUrl(url)}/index.min.json", forceRefresh, depth + 1)
				} else {
					storeEntries(json.decodeFromString<NetworkExtensionStore>(text), forceRefresh, depth)
				}
			}
			null -> emptyList()
			else -> storeEntries(protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes), forceRefresh, depth)
		}
	}

	private fun storeEntries(store: NetworkExtensionStore, forceRefresh: Boolean, depth: Int): List<ExternalExtensionRepoEntry> {
		store.extensionList?.let { return it.extensions.map(NetworkExtensionStore.Extension::toRepoEntry) }
		val listUrl = store.extensionListUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
		if (depth > MAX_INDEX_HOPS) return emptyList()
		val bytes = fetchBytes(listUrl, forceRefresh) ?: return emptyList()
		val list = when (bytes.firstOrNull()) {
			OPEN_BRACE -> json.decodeFromString<NetworkExtensionStore.ExtensionList>(bytes.decodeToString())
			null -> null
			else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes)
		}
		return list?.extensions?.map(NetworkExtensionStore.Extension::toRepoEntry).orEmpty()
	}

	/** Fetches [url], throwing on HTTP error; returns decompressed bytes, or null if the body is empty. */
	private fun fetchBytes(url: String, forceRefresh: Boolean): ByteArray? {
		val builder = Request.Builder().url(url).get()
		if (forceRefresh) {
			builder.cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
		}
		okHttpClient.newCall(builder.build()).execute().use { response ->
			if (!response.isSuccessful) {
				throw IllegalStateException("Unable to load repo: HTTP ${response.code}")
			}
			return response.body.bytes().gunzipIfNeeded().takeIf { it.isNotEmpty() }
		}
	}

	private fun ByteArray.gunzipIfNeeded(): ByteArray =
		if (size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()) {
			GZIPInputStream(inputStream()).use { it.readBytes() }
		} else {
			this
		}

	/**
	 * Fetches the repo's `repo.json` for its authoritative name + signing fingerprint. Returns null
	 * if the repo doesn't publish one (or it's unreachable) — callers then fall back to URL-derived
	 * naming and install-time provenance.
	 */
	suspend fun fetchRepoInfo(repoUrl: String): ExternalRepoInfo? = withContext(Dispatchers.IO) {
		runCatching {
			val bytes = fetchBytes("${getBaseUrl(repoUrl)}/repo.json", forceRefresh = false) ?: return@runCatching null
			parseRepoInfo(repoUrl, bytes.decodeToString())
		}.getOrNull()
	}

	/**
	 * Resolves the APK download URL for an extension.
	 * Follows the Mihon convention: APKs are stored at `${baseRepoUrl}/apk/${apkName}`.
	 * If [apkName] is already an absolute URL it is returned unchanged.
	 */
	fun resolveApkUrl(repoUrl: String, apkName: String): String {
		if (apkName.startsWith("http://") || apkName.startsWith("https://")) {
			return apkName
		}
		val base = getBaseUrl(repoUrl)
		return "$base/apk/$apkName"
	}

	/**
	 * Resolves the icon URL for an extension package.
	 * Icons are stored at `${baseRepoUrl}/icon/${packageName}.png`.
	 */
	fun resolveIconUrl(repoUrl: String, packageName: String): String {
		val base = getBaseUrl(repoUrl)
		return "$base/icon/$packageName.png"
	}

	/**
	 * Ensures the repo URL points to the index.min.json file.
	 * Accepts both the base URL and the full index URL.
	 */
	private fun buildIndexUrl(repoUrl: String): String {
		val base = repoUrl.trimEnd('/')
		return if (base.endsWith(".json")) base else "$base/index.min.json"
	}

	/**
	 * Returns the base repo URL (without the trailing /index.min.json or other filename).
	 */
	private fun getBaseUrl(repoUrl: String): String {
		val trimmed = repoUrl.trimEnd('/')
		return if (trimmed.endsWith(".json")) {
			trimmed.substringBeforeLast('/')
		} else {
			trimmed
		}
	}

	private companion object {
		const val OPEN_BRACKET: Byte = 91 // '[' — legacy JSON array index
		const val OPEN_BRACE: Byte = 123 // '{' — JSON object (repo.json or store); else protobuf
		const val MAX_INDEX_HOPS = 3
	}
}
