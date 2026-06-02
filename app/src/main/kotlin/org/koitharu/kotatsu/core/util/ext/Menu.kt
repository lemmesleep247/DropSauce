package org.koitharu.kotatsu.core.util.ext

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuItem
import org.koitharu.kotatsu.R
import java.lang.reflect.Method
import kotlin.math.min

fun Menu.setOptionalIconsVisibleCompat(isVisible: Boolean) {
	findOptionalIconsMethod()?.let { method ->
		runCatching { method.invoke(this, isVisible) }
	}
}

fun Menu.adjustPopupMenuIcons(resources: Resources, shouldSkip: (MenuItem) -> Boolean = { false }) {
	val iconSize = resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size)
	val textGap = resources.getDimensionPixelSize(R.dimen.menu_icon_text_spacing_extra)
	for (index in 0 until size()) {
		val item = getItem(index)
		item.icon?.let { icon ->
			if (icon !is PopupMenuIconDrawable && !shouldSkip(item)) {
				item.icon = PopupMenuIconDrawable(icon.mutate(), iconSize, textGap)
			}
		}
		item.subMenu?.adjustPopupMenuIcons(resources, shouldSkip)
	}
}

private fun Menu.findOptionalIconsMethod(): Method? {
	var currentClass: Class<*>? = javaClass
	while (currentClass != null) {
		val method = runCatching {
			currentClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.javaPrimitiveType)
		}.getOrNull()
		if (method != null) {
			method.isAccessible = true
			return method
		}
		currentClass = currentClass.superclass
	}
	return null
}

private class PopupMenuIconDrawable(
	private val source: Drawable,
	private val iconSize: Int,
	private val textGap: Int,
) : Drawable() {

	override fun draw(canvas: Canvas) {
		val bounds = bounds
		val size = min(iconSize, min(bounds.width(), bounds.height()))
		val left = bounds.left
		val top = bounds.top + (bounds.height() - size) / 2
		source.setBounds(left, top, left + size, top + size)
		source.draw(canvas)
	}

	override fun getIntrinsicWidth(): Int = iconSize + textGap

	override fun getIntrinsicHeight(): Int = iconSize

	override fun setAlpha(alpha: Int) {
		source.alpha = alpha
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		source.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	override fun isStateful(): Boolean = source.isStateful

	override fun onStateChange(state: IntArray): Boolean {
		return source.setState(state)
	}

	override fun onLevelChange(level: Int): Boolean {
		return source.setLevel(level)
	}
}
