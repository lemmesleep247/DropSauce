package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * A [SwipeRefreshLayout] that hides the legacy spinner and follows the pull gesture with a
 * Material 3 Expressive [LoadingIndicator] instead.
 */
class LoadingIndicatorSwipeRefreshLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

	private val indicator = LoadingIndicator(
		context,
		null,
		com.google.android.material.R.attr.loadingIndicatorStyle,
	).apply {
		// Always VISIBLE in the hierarchy; hidden via alpha so we never call
		// requestLayout() inside dispatchDraw(), which Android can silently drop.
		alpha = 0f
	}

	// When true, syncIndicator() keeps the indicator hidden even if the native circle
	// is still marked VISIBLE (i.e. stuck after a cancelled drag). Reset when a new
	// drag starts so the indicator shows normally on the next pull.
	private var forcedHidden = false

	// Posted 300 ms after the user lifts their finger without triggering a refresh.
	// The SwipeRefreshLayout cancel animation takes ~200 ms; this fires afterward and
	// forces the indicator off regardless of the native circle's visibility state.
	private val forceHideRunnable = Runnable {
		if (!isRefreshing) {
			forcedHidden = true
			indicator.alpha = 0f
		}
	}

	private val nativeCircle: ImageView?
		get() {
			for (i in 0 until childCount) {
				val child = getChildAt(i)
				if (child is ImageView) return child
			}
			return null
		}

	override fun onFinishInflate() {
		super.onFinishInflate()
		addView(indicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
	}

	/** Tints the Material 3 loading indicator (the native circle is hidden, so [setColorSchemeColors] is a no-op here). */
	fun setIndicatorColor(@ColorInt color: Int) {
		indicator.setIndicatorColor(color)
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		measureChild(indicator, widthMeasureSpec, heightMeasureSpec)
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		val w = indicator.measuredWidth
		val h = indicator.measuredHeight
		val l = (width - w) / 2
		indicator.layout(l, 0, l + w, h)
		syncIndicator()
	}

	override fun dispatchDraw(canvas: Canvas) {
		syncIndicator()
		super.dispatchDraw(canvas)
	}

	// ── Touch / nested-scroll hooks to reset or schedule the force-hide ──────────

	override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
		if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
			// New touch — user may be starting a drag. Clear forced state.
			forcedHidden = false
			removeCallbacks(forceHideRunnable)
		}
		return super.onInterceptTouchEvent(ev)
	}

	override fun onTouchEvent(ev: MotionEvent): Boolean {
		val result = super.onTouchEvent(ev)
		if (!isRefreshing &&
			(ev.actionMasked == MotionEvent.ACTION_UP ||
				ev.actionMasked == MotionEvent.ACTION_CANCEL)
		) {
			// User lifted finger; schedule a hard hide after the cancel animation.
			removeCallbacks(forceHideRunnable)
			postDelayed(forceHideRunnable, 500L)
		}
		return result
	}

	override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
		// Nested drag starting — clear any pending/active forced hide.
		forcedHidden = false
		removeCallbacks(forceHideRunnable)
		return super.onStartNestedScroll(child, target, axes, type)
	}

	override fun onStopNestedScroll(target: View, type: Int) {
		super.onStopNestedScroll(target, type)
		if (!isRefreshing) {
			removeCallbacks(forceHideRunnable)
			postDelayed(forceHideRunnable, 500L)
		}
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		removeCallbacks(forceHideRunnable)
	}

	// ─────────────────────────────────────────────────────────────────────────────

	private fun syncIndicator() {
		val circle = nativeCircle ?: return
		// Keep the built-in circle invisible; we draw our own indicator.
		if (circle.alpha != 0f) circle.alpha = 0f

		// If we've force-hidden after a cancelled drag, stay hidden until the next
		// drag starts — even if the native circle is still lingering as VISIBLE.
		if (forcedHidden && !isRefreshing) {
			indicator.alpha = 0f
			return
		}

		val shouldShow = isRefreshing || circle.isVisible
		// Use alpha (not visibility) — safe to call inside dispatchDraw().
		indicator.alpha = if (shouldShow) 1f else 0f
		if (shouldShow) {
			indicator.translationY = circle.y + (circle.height - indicator.height) / 2f
		}
	}
}
