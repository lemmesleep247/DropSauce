package org.koitharu.kotatsu.sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.domain.SyncApiException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Google Drive v3 REST client scoped to the hidden `appDataFolder`. Stores a single plain
 * JSON snapshot. Uses the app's shared OkHttp client (stripped of manga-specific interceptors);
 * auth tokens come from [GoogleDriveAuth].
 */
@Singleton
class GoogleDriveApi @Inject constructor(
	@BaseHttpClient baseHttpClient: OkHttpClient,
) {

	// A clean client for Google APIs: reuse the base DNS/proxy/timeouts but drop the manga-oriented
	// interceptors (Cloudflare, rate-limit, gzip) and the response cache, which can interfere with
	// these REST calls (e.g. returning stale results or mangling request/response bodies).
	private val httpClient = baseHttpClient.newBuilder().apply {
		interceptors().clear()
		networkInterceptors().clear()
		cache(null)
		connectTimeout(15, TimeUnit.SECONDS)
		readTimeout(30, TimeUnit.SECONDS)
		writeTimeout(30, TimeUnit.SECONDS)
		callTimeout(60, TimeUnit.SECONDS)
	}.build()

	private val json = Json { ignoreUnknownKeys = true }

	@Serializable
	class DriveFile(
		@SerialName("id") val id: String,
		@SerialName("name") val name: String? = null,
		@SerialName("modifiedTime") val modifiedTime: String? = null,
	)

	@Serializable
	class DriveUser(
		@SerialName("displayName") val displayName: String? = null,
		@SerialName("emailAddress") val emailAddress: String? = null,
		@SerialName("photoLink") val photoLink: String? = null,
	)

	@Serializable
	private class FileList(@SerialName("files") val files: List<DriveFile> = emptyList())

	@Serializable
	private class AboutResponse(@SerialName("user") val user: DriveUser? = null)

	@Serializable
	private class IdResponse(@SerialName("id") val id: String)

	/** Returns the signed-in user's profile, or null if it can't be resolved. */
	suspend fun getUser(token: String): DriveUser? = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/about".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "user")
			.build()
		val request = Request.Builder().url(url).get().authorize(token).build()
		httpClient.newCall(request).execute().parse<AboutResponse>()?.user
	}

	/** Finds the existing sync file in appDataFolder, or null. */
	suspend fun findSyncFile(token: String): DriveFile? = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
			.addQueryParameter("spaces", "appDataFolder")
			.addQueryParameter("q", "name = '$FILE_NAME'")
			.addQueryParameter("fields", "files(id,name,modifiedTime)")
			.addQueryParameter("pageSize", "10")
			.build()
		val request = Request.Builder().url(url).get().authorize(token).build()
		httpClient.newCall(request).execute().parse<FileList>()?.files?.firstOrNull()
	}

	/** Downloads the raw sync file content (plain JSON bytes). */
	suspend fun download(token: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
			.addQueryParameter("alt", "media")
			.build()
		val request = Request.Builder().url(url).get().authorize(token).build()
		httpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw response.toError()
			response.body.bytes()
		}
	}

	/**
	 * Uploads [content] (plain JSON bytes). Creates the file in appDataFolder when [fileId] is null
	 * (a metadata-only create followed by a media upload — avoids the fragile multipart path),
	 * otherwise overwrites the existing file. Returns the file id.
	 */
	suspend fun upload(token: String, content: ByteArray, fileId: String?): String =
		withContext(Dispatchers.IO) {
			val targetId = fileId ?: createEmptyFile(token)
			val url = "$UPLOAD_BASE/files/$targetId".toHttpUrl().newBuilder()
				.addQueryParameter("uploadType", "media")
				.addQueryParameter("fields", "id")
				.build()
			val request = Request.Builder()
				.url(url)
				.patch(content.toRequestBody(JSON_MEDIA_TYPE))
				.authorize(token)
				.build()
			httpClient.newCall(request).execute().parse<IdResponse>()?.id ?: targetId
		}

	private fun createEmptyFile(token: String): String {
		val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
		val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "id")
			.build()
		val request = Request.Builder()
			.url(url)
			.post(metadata.toRequestBody(JSON_MEDIA_TYPE))
			.authorize(token)
			.build()
		return httpClient.newCall(request).execute().parse<IdResponse>()?.id
			?: throw SyncApiException(0, "Failed to create sync file")
	}

	suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("$DRIVE_BASE/files/$fileId")
			.delete()
			.authorize(token)
			.build()
		httpClient.newCall(request).execute().use { response ->
			// 404 means it's already gone — treat as success.
			if (!response.isSuccessful && response.code != 404) throw response.toError()
		}
	}

	/** Revokes the OAuth grant so the next sign-in re-prompts (and can pick a different account). */
	suspend fun revokeToken(token: String) = withContext(Dispatchers.IO) {
		val url = "https://oauth2.googleapis.com/revoke".toHttpUrl().newBuilder()
			.addQueryParameter("token", token)
			.build()
		val request = Request.Builder()
			.url(url)
			.post(ByteArray(0).toRequestBody(FORM_MEDIA_TYPE))
			.build()
		runCatching { httpClient.newCall(request).execute().close() }
		Unit
	}

	private fun Request.Builder.authorize(token: String) = header("Authorization", "Bearer $token")

	private inline fun <reified T> Response.parse(): T? = use { response ->
		if (!response.isSuccessful) throw response.toError()
		val text = response.body.string()
		if (text.isBlank()) null else json.decodeFromString<T>(text)
	}

	private fun Response.toError(): SyncApiException {
		val message = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
		return SyncApiException(code, "Drive API error $code: $message")
	}

	private companion object {

		const val FILE_NAME = "dropsauce_sync.json"
		const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
		const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
		val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
		val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
	}
}
