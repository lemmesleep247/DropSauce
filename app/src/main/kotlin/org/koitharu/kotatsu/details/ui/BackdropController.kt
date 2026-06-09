package org.koitharu.kotatsu.details.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.size.Precision
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.lang.ref.WeakReference

class BackdropController(
	backdrop: ImageView,
	backdropGradient: View,
	backdropTopGradient: View,
	context: Context,
	private val imageLoader: ImageLoader,
	private val lifecycle: LifecycleOwner,
	private val settings: AppSettings,
	private val isExtended: Boolean = true,
) : DefaultLifecycleObserver {
	private val backdropRef = WeakReference(backdrop)
	private val gradientRef = WeakReference(backdropGradient)
	private val topGradientRef = WeakReference(backdropTopGradient)
	private var currentDisposable: Disposable? = null
	private val isDarkBackground: Boolean

	/**
	 * Invoked on the main thread once a vibrant accent color has been extracted from the loaded
	 * cover, already harmonized to be legible on the current theme background.
	 */
	var onColorExtracted: ((Int) -> Unit)? = null

	init {
		val bgColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).run {
			getColor(0, Color.WHITE).also { recycle() }
		}
		isDarkBackground = ColorUtils.calculateLuminance(bgColor) < 0.5
		applyGradients(bgColor)
		lifecycle.lifecycle.addObserver(this)
	}

	fun load(imageUrl: String?) {
		val backdrop = backdropRef.get() ?: return
		if (imageUrl.isNullOrBlank()) return
		currentDisposable?.dispose()
		val request = ImageRequest.Builder(backdrop.context)
			.data(imageUrl)
			.lifecycle(lifecycle)
			.crossfade(true)
			.allowHardware(false)
			.allowRgb565(true)
			.precision(Precision.INEXACT)
			.target(
				onSuccess = { image ->
					val view = backdropRef.get() ?: return@target
					// Uniform scale + no vertical offset: centerCrop fills the (tall) container without
					// distorting the cover. The 1.1 vertical scale used previously stretched the image.
					view.scaleX = 1f
					view.scaleY = 1f
					view.translationY = 0f
					val drawable = image.asDrawable(view.context.resources)
					view.animate().cancel()
					view.alpha = 0f
					view.setImageDrawable(drawable)
					applyBlur(view)
					extractAccent(drawable)
					// Extended mode dims the backdrop (it sits behind text); the classic look stays full.
					view.animate()
						.alpha(if (isExtended) BACKDROP_ALPHA else 1f)
						.setDuration(CROSSFADE_DURATION_MS)
						.setInterpolator(android.view.animation.DecelerateInterpolator())
						.start()
				},
			).build()
		currentDisposable = imageLoader.enqueue(request)
	}

	override fun onDestroy(owner: LifecycleOwner) {
		currentDisposable?.dispose()
		currentDisposable = null
		owner.lifecycle.removeObserver(this)
	}

	private fun applyGradients(surfaceColor: Int) {
		fun alpha(a: Int) = ColorUtils.setAlphaComponent(surfaceColor, a)
		val bottomStops = if (isExtended) {
			// Even, gradual ramp (mirror of the top fade) so the extended backdrop fades smoothly
			// instead of snapping to opaque near the top of the gradient (which looked like a hard line).
			intArrayOf(
				Color.TRANSPARENT,
				alpha(30), alpha(80), alpha(140),
				alpha(200), alpha(240), surfaceColor,
			)
		} else {
			// Classic look: a short, denser fade right under the cover (as before this redesign).
			intArrayOf(
				Color.TRANSPARENT,
				alpha(25), alpha(50), alpha(100),
				alpha(160), alpha(210), alpha(240),
				alpha(248), alpha(253), surfaceColor,
			)
		}
		gradientRef.get()?.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, bottomStops)
		topGradientRef.get()?.background = GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			intArrayOf(
				surfaceColor,
				alpha(240), alpha(200), alpha(140),
				alpha(80), alpha(30), Color.TRANSPARENT,
			),
		)
	}

	@Suppress("DEPRECATION")
	private fun applyBlur(view: ImageView) {
		val amount = settings.backdropBlurAmount
		if (amount <= 0) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				view.setRenderEffect(null)
			}
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val radius = blurRadius(amount, MAX_BLUR_RADIUS_API31)
			view.setRenderEffect(
				RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR),
			)
			return
		}

		val bitmap = drawableToBitmap(view.drawable ?: return)
		val scaled = bitmap.scale(
			(bitmap.width * BLUR_SCALE_FACTOR).toInt().coerceAtLeast(1),
			(bitmap.height * BLUR_SCALE_FACTOR).toInt().coerceAtLeast(1),
		)
		if (bitmap !== scaled) bitmap.recycle()
		val rsRadius = blurRadius(amount, MAX_BLUR_RADIUS_RS).coerceIn(1f, MAX_BLUR_RADIUS_RS)
		android.renderscript.RenderScript.create(view.context).also { rs ->
			val input = android.renderscript.Allocation.createFromBitmap(rs, scaled)
			val output = android.renderscript.Allocation.createTyped(rs, input.type)
			android.renderscript.ScriptIntrinsicBlur.create(rs, android.renderscript.Element.U8_4(rs))
				.apply {
					setRadius(rsRadius)
					setInput(input)
					forEach(output)
				}
			output.copyTo(scaled)
			rs.destroy()
		}
		view.setImageBitmap(scaled)
	}

	private fun extractAccent(drawable: Drawable) {
		if (onColorExtracted == null) return
		val bitmap = try {
			drawableToBitmap(drawable)
		} catch (_: Throwable) {
			return
		}
		Palette.from(bitmap)
			.maximumColorCount(24)
			.generate { palette ->
				val cb = onColorExtracted ?: return@generate
				palette ?: return@generate
				val raw = palette.vibrantSwatch?.rgb
					?: palette.lightVibrantSwatch?.rgb
					?: palette.darkVibrantSwatch?.rgb
					?: palette.mutedSwatch?.rgb
					?: palette.dominantSwatch?.rgb
					?: return@generate
				cb(harmonizeAccent(raw))
			}
	}

	/**
	 * Pushes the extracted color into a saturation/lightness band that stays legible on the
	 * current theme background, so the accent reads well in both light and dark mode.
	 */
	private fun harmonizeAccent(color: Int): Int {
		val hsl = FloatArray(3)
		ColorUtils.colorToHSL(color, hsl)
		hsl[1] = hsl[1].coerceIn(0.4f, 0.95f)
		hsl[2] = if (isDarkBackground) {
			hsl[2].coerceIn(0.62f, 0.82f)
		} else {
			hsl[2].coerceIn(0.3f, 0.46f)
		}
		return ColorUtils.HSLToColor(hsl)
	}

	private fun drawableToBitmap(drawable: Drawable): Bitmap {
		if (drawable is android.graphics.drawable.BitmapDrawable)
			return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, true)
		return androidx.core.graphics.createBitmap(
			drawable.intrinsicWidth.coerceAtLeast(1),
			drawable.intrinsicHeight.coerceAtLeast(1),
		).also { bitmap ->
			val canvas = android.graphics.Canvas(bitmap)
			drawable.setBounds(0, 0, canvas.width, canvas.height)
			drawable.draw(canvas)
		}
	}

	companion object {
		private const val CROSSFADE_DURATION_MS = 400L

		// Uniform dim applied to the backdrop so it blends toward the theme surface, keeping the title
		// and other content over it legible. Lower = dimmer.
		private const val BACKDROP_ALPHA = 0.65f
		private const val BLUR_SCALE_FACTOR = 0.4f
		private const val MAX_BLUR_RADIUS_API31 = 25f
		private const val MAX_BLUR_RADIUS_RS = 25f

		fun blurRadius(amount: Int, maxRadius: Float): Float =
			(amount / 100f) * maxRadius
	}
}
