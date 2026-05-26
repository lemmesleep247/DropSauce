package org.koitharu.kotatsu.main.ui.nav

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Lightweight [Painter] that delegates drawing to an Android [Drawable]. Lets us paint
 * state-list selector drawables (which `painterResource` doesn't accept) directly into
 * the Compose canvas — no AndroidView interop, no per-frame `ColorStateList` allocation.
 *
 * Listens to the drawable's invalidate callback so animated-state-list transitions still
 * trigger Compose re-renders.
 */
class DrawablePainter(val drawable: Drawable) : Painter(), RememberObserver {

	private var invalidateTick by mutableIntStateOf(0)

	private val drawableCallback = object : Drawable.Callback {
		override fun invalidateDrawable(who: Drawable) {
			invalidateTick++
		}

		override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
		override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
	}

	override val intrinsicSize: Size
		get() {
			val w = drawable.intrinsicWidth
			val h = drawable.intrinsicHeight
			return if (w > 0 && h > 0) Size(w.toFloat(), h.toFloat()) else Size.Unspecified
		}

	override fun DrawScope.onDraw() {
		// Subscribe to invalidation so animated-state-list transitions redraw.
		invalidateTick
		drawIntoCanvas { canvas ->
			drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
			drawable.draw(canvas.nativeCanvas)
		}
	}

	override fun applyAlpha(alpha: Float): Boolean {
		drawable.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
		return true
	}

	override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
		drawable.colorFilter = colorFilter?.asAndroidColorFilter()
		return true
	}

	override fun onRemembered() {
		drawable.callback = drawableCallback
	}

	override fun onForgotten() {
		drawable.callback = null
	}

	override fun onAbandoned() {
		drawable.callback = null
	}
}

private val SELECTED_STATES = intArrayOf(
	android.R.attr.state_checked,
	android.R.attr.state_selected,
	android.R.attr.state_enabled,
)
private val UNSELECTED_STATES = intArrayOf(android.R.attr.state_enabled)

/** Remember a [DrawablePainter] for [resId]; push checked/unchecked state when [selected] flips. */
@Composable
fun rememberSelectorPainter(@DrawableRes resId: Int, selected: Boolean): DrawablePainter {
	val context = LocalContext.current
	val painter = remember(resId) {
		val d = ContextCompat.getDrawable(context, resId)!!.mutate()
		DrawablePainter(d)
	}
	SideEffect {
		painter.drawable.state = if (selected) SELECTED_STATES else UNSELECTED_STATES
	}
	return painter
}
