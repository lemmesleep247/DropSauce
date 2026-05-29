package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.loadingindicator.LoadingIndicator

/**
 * A [SwipeRefreshLayout] that hides the legacy spinner and follows the pull gesture with a
 * Material 3 Expressive [LoadingIndicator] instead.
 *
 * SwipeRefreshLayout keeps driving the gesture, nested scrolling and the refresh trigger via its
 * own circle view; we keep that circle fully invisible and mirror its vertical position onto our
 * indicator.
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
		isVisible = false
	}

	private val nativeCircle: ImageView?
		get() {
			for (i in 0 until childCount) {
				val child = getChildAt(i)
				if (child is ImageView) {
					return child
				}
			}
			return null
		}

	override fun onFinishInflate() {
		super.onFinishInflate()
		// Added after the content child so SwipeRefreshLayout still resolves the scrollable target.
		addView(indicator, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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

	private fun syncIndicator() {
		val circle = nativeCircle ?: return
		// Always keep the built-in arrow spinner invisible; we draw our own indicator.
		if (circle.alpha != 0f) {
			circle.alpha = 0f
		}
		// SwipeRefreshLayout keeps its circle GONE at rest and VISIBLE while dragging/refreshing,
		// so its visibility is a reliable signal regardless of the configured offset.
		val shouldShow = isRefreshing || circle.isVisible
		if (indicator.isVisible != shouldShow) {
			indicator.isVisible = shouldShow
		}
		if (shouldShow) {
			// circle.y is its current top (layout offset + drag translation); follow it.
			indicator.translationY = circle.y + (circle.height - indicator.height) / 2f
		}
	}
}
