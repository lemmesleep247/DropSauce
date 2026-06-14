package org.koitharu.kotatsu.core.ui.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import com.google.android.material.R as materialR

/**
 * Applies the app's unified top-bar treatment to a [Toolbar]: a circular tonal navigation
 * (back/close) button on the start side and a tonal "pill" grouping the action icons on the end
 * side. Both share the same height, fill colour and icon tint so the bar reads as a single,
 * consistent design language regardless of which screen it belongs to.
 */
fun Toolbar.applyTonalTopBarStyle() {
	applyTonalNavigationButtonStyle()
	applyTonalActionMenuStyle()
}

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
			marginStart = startMargin
		}
		navigationButton.background = context.createTonalNavigationBackground()
		navigationButton.imageTintList = ColorStateList.valueOf(
			context.getThemeColor(materialR.attr.colorOnSurfaceVariant),
		)
		contentInsetStartWithNavigation = titleInset
	}
}

/**
 * Styles an arbitrary icon button (e.g. the contextual action-mode close "X") to match the
 * circular tonal navigation buttons used by the app's top bars.
 */
fun ImageView.applyTonalIconButtonStyle() {
	val size = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size)
	val startMargin = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_margin_start)
	val iconSize = resources.getDimensionPixelSize(R.dimen.top_bar_action_icon_size)
	val padding = ((size - iconSize) / 2).coerceAtLeast(0)
	minimumWidth = 0
	minimumHeight = 0
	updateLayoutParams<ViewGroup.MarginLayoutParams> {
		width = size
		height = size
		marginStart = startMargin
	}
	setPadding(padding, padding, padding, padding)
	scaleType = ImageView.ScaleType.FIT_CENTER
	background = context.createTonalNavigationBackground()
	imageTintList = ColorStateList.valueOf(context.getThemeColor(materialR.attr.colorOnSurfaceVariant))
}

/**
 * Groups the action (menu) icons of a top bar into a single tonal pill that mirrors the circular
 * navigation button: identical height and fill colour, an end inset that matches the navigation
 * button's start margin, and one equally-sized circular ripple per icon with no gaps between them.
 *
 * Works for both a [Toolbar] and the contextual action-mode bar (`ActionBarContextView`), since
 * both host their items inside an [ActionMenuView]. The menu is populated asynchronously (and may
 * change at runtime), so styling is re-applied on every layout pass; it is idempotent and only
 * touches not-yet-styled views, so it converges without triggering relayout loops.
 */
fun ViewGroup.applyTonalActionMenuStyle() {
	if (getTag(R.id.tag_tonal_action_menu) == null) {
		setTag(R.id.tag_tonal_action_menu, true)
		addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
			(view as? ViewGroup)?.findActionMenuView()?.applyTonalPillStyle()
		}
	}
	post { findActionMenuView()?.applyTonalPillStyle() }
}

private fun ActionMenuView.applyTonalPillStyle() {
	val size = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size)
	val endMargin = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_margin_end)
	val iconSize = resources.getDimensionPixelSize(R.dimen.top_bar_action_icon_size)
	val iconPadding = ((size - iconSize) / 2).coerceAtLeast(0)
	val iconTint = ColorStateList.valueOf(context.getThemeColor(materialR.attr.colorOnSurfaceVariant))

	// One-time container setup: the pill fill, no internal padding, and center children vertically
	// so they always sit centered no matter how tall the container ends up being measured.
	if (getTag(R.id.tag_tonal_action_pill) == null) {
		setTag(R.id.tag_tonal_action_pill, true)
		setPadding(0, 0, 0, 0)
		background = context.createTonalPillBackground(size)
		clipToOutline = true
		gravity = Gravity.CENTER_VERTICAL
	}
	// Pin the container to exactly the navigation-button height, center it in the bar, and mirror
	// the navigation button's start margin on the end side. The default container fills the whole
	// bar height (MATCH_PARENT), which is what made the pill too tall and the icons top-aligned.
	val lp = layoutParams ?: return
	var lpChanged = false
	if (lp.height != size) {
		lp.height = size
		lpChanged = true
	}
	val targetGravity = Gravity.END or Gravity.CENTER_VERTICAL
	if (lp is Toolbar.LayoutParams && lp.gravity != targetGravity) {
		lp.gravity = targetGravity
		lpChanged = true
	}
	if (lp is ViewGroup.MarginLayoutParams && lp.marginEnd != endMargin) {
		lp.marginEnd = endMargin
		lpChanged = true
	}
	if (lpChanged) {
		layoutParams = lp
	}
	for (child in children) {
		if (!child.isTonalActionItem()) {
			continue
		}
		child.applyTonalActionItemLayout(size, iconPadding, iconTint)
		// ActionMenuItemView icons are re-forced whenever the underlying drawable changes (menu
		// re-binds can swap it back to its intrinsic size), but skipped otherwise to avoid relayout.
		if (child is TextView) {
			child.forceTonalCompoundIcon(iconSize, iconTint)
		}
	}
}

/** Structural sizing for one item: a fixed square cell with a bounded circular ripple. */
private fun View.applyTonalActionItemLayout(cell: Int, iconPadding: Int, tint: ColorStateList) {
	if (getTag(R.id.tag_tonal_action_item) != null) {
		return
	}
	setTag(R.id.tag_tonal_action_item, true)
	minimumWidth = 0
	minimumHeight = 0
	updateLayoutParams<ViewGroup.MarginLayoutParams> {
		width = cell
		height = cell
		marginStart = 0
		marginEnd = 0
	}
	background = context.createTonalActionItemBackground()
	when (this) {
		is ImageView -> {
			// Overflow button & image action views: scale the drawable into a uniform icon box.
			scaleType = ImageView.ScaleType.FIT_CENTER
			setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
			imageTintList = tint
		}
		is TextView -> {
			// Icon-only ActionMenuItemView: the icon is a centered compound drawable, sized below.
			gravity = Gravity.CENTER
			compoundDrawablePadding = 0
			setPadding(0, 0, 0, 0)
		}
	}
}

/** Only icon-only items belong in the pill; text actions keep their default (wider) layout. */
private fun View.isTonalActionItem(): Boolean = when (this) {
	is ImageView -> true // overflow button & image action views
	is TextView -> text.isNullOrEmpty() // icon-only ActionMenuItemView (a TextView subclass)
	else -> false
}

/**
 * Forces an [ActionMenuItemView]'s icon to a uniform size and tint so every icon matches the back
 * button regardless of its drawable's intrinsic size. Re-applied only when the drawable instance
 * changes (tracked via a tag) so steady-state layout passes don't loop.
 */
private fun TextView.forceTonalCompoundIcon(iconSize: Int, tint: ColorStateList) {
	val current = compoundDrawables.firstOrNull { it != null }
	if (current == null || getTag(R.id.tag_tonal_action_icon) === current) {
		return
	}
	val sized = current.mutate().apply {
		setBounds(0, 0, iconSize, iconSize)
		setTintList(tint)
	}
	// setCompoundDrawables (not …WithIntrinsicBounds) keeps the explicit icon bounds.
	setCompoundDrawables(sized, null, null, null)
	setTag(R.id.tag_tonal_action_icon, sized)
}

private fun ViewGroup.findActionMenuView(): ActionMenuView? =
	children.filterIsInstance<ActionMenuView>().firstOrNull()

private fun Toolbar.findNavigationButton(): ImageButton? {
	navigationIcon ?: return null
	return children
		.filterIsInstance<ImageButton>()
		.firstOrNull()
}

private fun Context.createTonalNavigationBackground(): RippleDrawable {
	val shape = createCircleDrawable(getThemeColor(materialR.attr.colorSurfaceContainer))
	val mask = createCircleDrawable(Color.WHITE)
	return RippleDrawable(
		ColorStateList.valueOf(getThemeColor(android.R.attr.colorControlHighlight)),
		shape,
		mask,
	)
}

/**
 * Transparent-bodied circular ripple for an item inside the pill: the pill itself supplies the
 * fill, so only the (bounded, centered) press highlight is drawn here.
 */
private fun Context.createTonalActionItemBackground(): RippleDrawable {
	val mask = createCircleDrawable(Color.WHITE)
	return RippleDrawable(
		ColorStateList.valueOf(getThemeColor(android.R.attr.colorControlHighlight)),
		null,
		mask,
	)
}

private fun Context.createTonalPillBackground(heightPx: Int) = GradientDrawable().apply {
	shape = GradientDrawable.RECTANGLE
	cornerRadius = heightPx / 2f
	setColor(getThemeColor(materialR.attr.colorSurfaceContainer))
}

private fun createCircleDrawable(color: Int) = GradientDrawable().apply {
	shape = GradientDrawable.OVAL
	setColor(color)
}
