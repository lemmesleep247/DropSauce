package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import kotlinx.coroutines.runInterruptible
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder

/**
 * Mihon's native decoder fallback for formats Android/Coil do not reliably decode. This is part of
 * the extension data path only; it does not alter reader UI or layout behavior.
 */
class MihonFallbackImageDecoder(
	private val source: ImageSource,
	private val options: Options,
) : Decoder {

	override suspend fun decode(): DecodeResult = runInterruptible {
		val decoder = checkNotNull(ImageDecoder.newInstance(source.source().inputStream(), false, null))
		check(decoder.width > 0 && decoder.height > 0) { "Failed to initialize Mihon image decoder" }
		try {
			val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
				srcWidth = decoder.width,
				srcHeight = decoder.height,
				targetSize = options.size,
				scale = options.scale,
				maxSize = options.maxBitmapSize,
			)
			val sampleSize = DecodeUtils.calculateInSampleSize(
				srcWidth = decoder.width,
				srcHeight = decoder.height,
				dstWidth = dstWidth,
				dstHeight = dstHeight,
				scale = options.scale,
			)
			var bitmap = checkNotNull(decoder.decode(sampleSize = sampleSize))
			if (options.bitmapConfig == Bitmap.Config.HARDWARE) {
				bitmap.copy(Bitmap.Config.HARDWARE, false)?.let { hardware ->
					bitmap.recycle()
					bitmap = hardware
				}
			}
			DecodeResult(bitmap.asImage(), isSampled = sampleSize > 1)
		} finally {
			decoder.recycle()
		}
	}

	class Factory : Decoder.Factory {
		override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
			val bytes = result.source.source().peek().readByteArray(32)
			val format = ImageDecoder.findType(bytes)?.format
			return if (format == Format.Jxl || format == Format.Heif) {
				MihonFallbackImageDecoder(result.source, options)
			} else {
				null
			}
		}
	}
}
