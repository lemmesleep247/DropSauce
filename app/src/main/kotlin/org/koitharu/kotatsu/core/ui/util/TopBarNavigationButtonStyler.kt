package org.koitharu.kotatsu.core.ui.util

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.widget.ImageButton
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MarginLayoutParamsCompat
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import com.google.android.material.R as materialR

fun Toolbar.applyTonalNavigationButtonStyle() {
	post {
		val navigationButton = findNavigationButton() ?: return@post
		val size = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size)
		val startMargin = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_margin_start)
		val titleInset = resources.getDimensionPixelSize(R.dimen.top_bar_title_inset_with_navigation)

		navigationButton.updateLayoutParams<Toolbar.LayoutParams> {
			width = size
			height = size
			gravity = Gravity.START or Gravity.CENTER_VERTICAL
			MarginLayoutParamsCompat.setMarginStart(this, startMargin)
		}
		navigationButton.background = createTonalNavigationBackground()
		navigationButton.imageTintList = ColorStateList.valueOf(
			context.getThemeColor(materialR.attr.colorOnSurfaceVariant),
		)
		contentInsetStartWithNavigation = titleInset
	}
}

private fun Toolbar.findNavigationButton(): ImageButton? {
	navigationIcon ?: return null
	return children
		.filterIsInstance<ImageButton>()
		.firstOrNull()
}

private fun Toolbar.createTonalNavigationBackground() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
	val shape = createCircleDrawable(context.getThemeColor(materialR.attr.colorSurfaceContainer))
	val mask = createCircleDrawable(0xFFFFFFFF.toInt())
	RippleDrawable(
		ColorStateList.valueOf(context.getThemeColor(android.R.attr.colorControlHighlight)),
		shape,
		mask,
	)
} else {
	createCircleDrawable(context.getThemeColor(materialR.attr.colorSurfaceContainer))
}

private fun createCircleDrawable(color: Int) = GradientDrawable().apply {
	shape = GradientDrawable.OVAL
	setColor(color)
}
