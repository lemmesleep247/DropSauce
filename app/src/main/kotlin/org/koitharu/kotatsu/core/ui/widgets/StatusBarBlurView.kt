package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.R

/**
 * Edge-to-edge status bar protection: blurs the content scrolling underneath the (transparent)
 * status bar and fades it out just below, so system icons stay legible without a solid header bar.
 * On devices below Android 12 (no RenderEffect) it falls back to a plain surface-colour gradient.
 *
 * Set [blurTarget] to the scrolling content view (it must be a sibling drawn below this view,
 * never an ancestor). The view sizes its own height to the status bar inset plus a small tail.
 */
class StatusBarBlurView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	var blurTarget: View? = null
		set(value) {
			field = value
			invalidate()
		}

	private var topInset = 0
	private val extraHeight = resources.getDimensionPixelOffset(R.dimen.margin_normal)
	private val surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)

	private val blurNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		RenderNode("statusBarBlur").apply {
			setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP))
		}
	} else {
		null
	}

	// Fallback for API < 31: the pre-existing multi-stop surface gradient.
	private val fallbackDrawable = if (blurNode == null) {
		val alphas = floatArrayOf(0.85f, 0.62f, 0.4f, 0.22f, 0.1f, 0f)
		GradientDrawable(
			GradientDrawable.Orientation.TOP_BOTTOM,
			IntArray(alphas.size) { i -> ColorUtils.setAlphaComponent(surfaceColor, (alphas[i] * 255f).toInt()) },
		)
	} else {
		null
	}

	private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
	private val tintPaint = Paint()
	private val myLocation = IntArray(2)
	private val targetLocation = IntArray(2)
	private var lastTargetTop = Int.MIN_VALUE

	// Redraw only when the content underneath actually changed (scroll offsets the target or
	// dirties it); an unconditional invalidate here would loop the render thread forever.
	private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
		val target = blurTarget
		if (target != null && (target.isDirty || target.top != lastTargetTop)) {
			invalidate()
		}
		true
	}

	init {
		setWillNotDraw(false)
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		viewTreeObserver.addOnPreDrawListener(preDrawListener)
	}

	override fun onDetachedFromWindow() {
		viewTreeObserver.removeOnPreDrawListener(preDrawListener)
		super.onDetachedFromWindow()
	}

	override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
		val newTop = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
			.getInsets(WindowInsetsCompat.Type.systemBars()).top
		if (newTop != topInset) {
			topInset = newTop
			requestLayout()
		}
		return super.onApplyWindowInsets(insets)
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (topInset + extraHeight) * 6 / 5)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		if (h <= 0) {
			return
		}
		val height = h.toFloat()
		// Fully opaque over the upper part of the status bar, then a long smooth-stepped fade so the
		// blur dissolves into the untouched content instead of ending in a visible band.
		val opaque = 0xFFFFFFFF.toInt()
		val fadeAlphas = floatArrayOf(1f, 1f, 0.9f, 0.66f, 0.38f, 0.16f, 0.04f, 0f)
		val fadeStops = floatArrayOf(0f, 0.45f, 0.58f, 0.69f, 0.79f, 0.88f, 0.95f, 1f)
		fadePaint.shader = LinearGradient(
			0f, 0f, 0f, height,
			IntArray(fadeAlphas.size) { i -> ColorUtils.setAlphaComponent(opaque, (fadeAlphas[i] * 255f).toInt()) },
			fadeStops,
			Shader.TileMode.CLAMP,
		)
		// Subtle surface tint over the blur so light content keeps enough contrast with the icons.
		tintPaint.shader = LinearGradient(
			0f, 0f, 0f, height,
			ColorUtils.setAlphaComponent(surfaceColor, TINT_ALPHA),
			ColorUtils.setAlphaComponent(surfaceColor, 0),
			Shader.TileMode.CLAMP,
		)
	}

	override fun onDraw(canvas: Canvas) {
		val target = blurTarget
		if (width <= 0 || height <= 0) {
			return
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
			blurNode == null || target == null || !target.isLaidOut || !canvas.isHardwareAccelerated
		) {
			fallbackDrawable?.run {
				setBounds(0, 0, width, height)
				draw(canvas)
			}
			return
		}
		drawBlur(canvas, blurNode, target)
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private fun drawBlur(canvas: Canvas, blurNode: RenderNode, target: View) {
		lastTargetTop = target.top
		// Record a slightly larger slice than shown (taller below, and wider on both sides) so the
		// blur has real pixels to sample at every edge instead of clamping, which would otherwise
		// leave an under-blurred strip down the left/right of the screen and a band at the fade.
		val margin = BLUR_RADIUS.toInt()
		val captureHeight = height + margin
		val captureWidth = width + margin * 2
		blurNode.setPosition(-margin, 0, width + margin, captureHeight)
		val recording = blurNode.beginRecording(captureWidth, captureHeight)
		try {
			getLocationInWindow(myLocation)
			target.getLocationInWindow(targetLocation)
			recording.translate(
				(targetLocation[0] - myLocation[0] + margin).toFloat(),
				(targetLocation[1] - myLocation[1]).toFloat(),
			)
			target.draw(recording)
		} finally {
			blurNode.endRecording()
		}
		val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
		canvas.drawRenderNode(blurNode)
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fadePaint)
		canvas.restoreToCount(checkpoint)
	}

	private companion object {
		const val BLUR_RADIUS = 50f
		const val TINT_ALPHA = 90 // ~35%
	}
}
