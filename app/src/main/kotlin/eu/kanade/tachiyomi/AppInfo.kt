package eu.kanade.tachiyomi

import org.koitharu.kotatsu.BuildConfig

/**
 * Provides host application info for extensions.
 *
 * @since extension-lib 1.3
 */
object AppInfo {
	fun getVersionCode(): Int = BuildConfig.VERSION_CODE
	fun getVersionName(): String = BuildConfig.VERSION_NAME

	/**
	 * A list of image MIME types supported by the reader.
	 *
	 * @since extension-lib 1.5
	 */
	fun getSupportedImageMimeTypes(): List<String> = listOf(
		"image/jpeg",
		"image/png",
		"image/gif",
		"image/webp",
		"image/avif",
		"image/jxl",
		"image/heif",
	)
}
