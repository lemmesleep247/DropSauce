package org.koitharu.kotatsu.main.ui.nav

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
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

private val MAIN_HANDLER = Handler(Looper.getMainLooper())

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

		// AnimatedVectorDrawable advances frames by calling scheduleSelf, which routes here.
		// Forward to the main-thread handler so the avd_*_enter / avd_*_leave morphs actually
		// tick — without this, state changes but no animation plays.
		override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
			MAIN_HANDLER.postAtTime(what, who, `when`)
		}

		override fun unscheduleDrawable(who: Drawable, what: Runnable) {
			MAIN_HANDLER.removeCallbacks(what, who)
		}
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
		// AnimatedVectorDrawable refuses to advance its animator unless the drawable is
		// marked visible. ContextCompat.getDrawable + mutate() leaves visibility at the
		// platform default (often false in this code path), so the avd_*_enter / _leave
		// transitions start but never tick. Force it true here.
		drawable.setVisible(true, true)
	}

	override fun onForgotten() {
		drawable.setVisible(false, false)
		drawable.callback = null
	}

	override fun onAbandoned() {
		drawable.setVisible(false, false)
		drawable.callback = null
	}
}

/**
 * Remember a [DrawablePainter] for any drawable resource — works with bitmaps, vectors,
 * AND `<bitmap>`/`<inset>`/`<layer-list>` XML wrappers that `painterResource` can't load.
 */
@Composable
fun rememberAnyDrawablePainter(@DrawableRes resId: Int): DrawablePainter {
	val context = LocalContext.current
	return remember(context, resId) {
		val d = ContextCompat.getDrawable(context, resId)!!.mutate()
		DrawablePainter(d)
	}
}

private val SELECTED_STATES = intArrayOf(
	android.R.attr.state_checked,
	android.R.attr.state_selected,
	android.R.attr.state_enabled,
)
private val UNSELECTED_STATES = intArrayOf(android.R.attr.state_enabled)

/**
 * Remember a [DrawablePainter] for [resId] and push checked/unchecked state through to it.
 *
 * The painter is keyed *only* on [resId] (not on [selected]) on purpose — for
 * `<animated-selector>` drawables, the inter-state morph animation only plays when state
 * changes on the SAME [Drawable] instance. Creating a fresh drawable per selection would
 * land it already in the target state with no transition.
 *
 * First composition is primed via [Drawable.setState] without recording the previous-state
 * jump as a transition (we explicitly set it twice in onRemembered's path), so the morph
 * only fires when the user actually toggles selection.
 */
@Composable
fun rememberSelectorPainter(@DrawableRes resId: Int, selected: Boolean): DrawablePainter {
	val context = LocalContext.current
	val painter = remember(context, resId) {
		val d = ContextCompat.getDrawable(context, resId)!!.mutate()
		// Prime initial state without a transition. Setting state twice (any state, then the
		// real one) is the standard trick to suppress AnimatedStateListDrawable's first
		// transition on inflate.
		d.state = intArrayOf()
		d.jumpToCurrentState()
		DrawablePainter(d)
	}
	SideEffect {
		val target = if (selected) SELECTED_STATES else UNSELECTED_STATES
		val current = painter.drawable.state
		if (!current.contentEquals(target)) {
			painter.drawable.state = target
		}
	}
	return painter
}
