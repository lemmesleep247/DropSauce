package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import org.koitharu.kotatsu.mihon.model.MihonLoadResult

@Serializable
data class ExternalExtensionRepoEntry(
	@SerialName("name") val name: String,
	@SerialName("pkg") val packageName: String,
	@SerialName("apk") val apkName: String,
	@SerialName("lang") val lang: String? = null,
	@SerialName("code") val versionCode: Long,
	@SerialName("version") val versionName: String,
	@SerialName("nsfw") val isNsfw: Int = 0,
	/** The catalogue sources this extension provides — lets us map a `MIHON_<id>` library entry
	 *  back to its installable package + display name regardless of where the entry came from. */
	@SerialName("sources") val sources: List<ExternalExtensionRepoSource> = emptyList(),
)

@Serializable
data class ExternalExtensionRepoSource(
	@SerialName("id") val id: String = "",
	@SerialName("name") val name: String = "",
	@SerialName("lang") val lang: String? = null,
)

/**
 * A source-api ABI bump is an update even when the extension's own version code is unchanged.
 */
fun ExternalExtensionRepoEntry.isNewerThan(local: MihonExtensionInfo): Boolean {
	return isNewerThan(local.versionCode, local.libVersion)
}

fun ExternalExtensionRepoEntry.isNewerThan(local: MihonLoadResult.Success): Boolean {
	return isNewerThan(local.versionCode, local.libVersion)
}

private fun ExternalExtensionRepoEntry.isNewerThan(localVersionCode: Long, localLibVersion: Double): Boolean {
	val availableLibVersion = versionName.split('.').take(2).joinToString(".").toDoubleOrNull() ?: 0.0
	return versionCode > localVersionCode || availableLibVersion > localLibVersion
}
