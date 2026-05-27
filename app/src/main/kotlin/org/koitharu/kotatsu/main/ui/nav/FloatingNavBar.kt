package org.koitharu.kotatsu.main.ui.nav

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

data class FloatingNavBarItem(
	@IdRes val id: Int,
	val titleRes: Int,
	@DrawableRes val icon: Int,
	val badgeCount: Int = 0,
)

// Material 3 "expressive" default spatial spring — snappier than the standard Compose default,
// keeps icon, color, label-expand, and sibling-resize all on the same beat.
private val FloatSpec_Float = spring<Float>(
	dampingRatio = 0.9f,
	stiffness = 380f,
)
private val FloatSpec_Color = spring<Color>(
	dampingRatio = 0.9f,
	stiffness = 380f,
)
private val FloatSpec_Size = spring<IntSize>(
	dampingRatio = 0.9f,
	stiffness = 380f,
)

@Composable
fun FloatingNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	amoled: Boolean,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (items.isEmpty()) return
	val cs = MaterialTheme.colorScheme
	val useAmoledBlack = amoled && isSystemInDarkTheme()
	val barColor = if (useAmoledBlack) Color.Black else cs.surfaceContainer
	val borderColor = if (useAmoledBlack) cs.outline.copy(alpha = 0.55f)
	else cs.outlineVariant.copy(alpha = 0.35f)

	Surface(
		modifier = modifier
			.shadow(8.dp, RoundedCornerShape(50))
			.wrapContentWidth(),
		shape = RoundedCornerShape(50),
		color = barColor,
		contentColor = cs.onSurface,
		border = BorderStroke(1.dp, borderColor),
	) {
		Row(
			modifier = Modifier
				.heightIn(min = 64.dp)
				.padding(horizontal = 8.dp, vertical = 8.dp)
				// Smoothly relayout siblings when one pill grows/shrinks horizontally.
				.animateContentSize(animationSpec = FloatSpec_Size),
			horizontalArrangement = Arrangement.spacedBy(4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			items.forEach { item ->
				FloatingNavItem(
					item = item,
					selected = item.id == selectedId,
					showLabel = showLabels,
					onClick = {
						if (item.id == selectedId) onItemReselected(item.id)
						else onItemSelected(item.id)
					},
				)
			}
		}
	}
}

@Composable
private fun FloatingNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	showLabel: Boolean,
	onClick: () -> Unit,
) {
	val cs = MaterialTheme.colorScheme
	val container by animateColorAsState(
		targetValue = if (selected) cs.primaryContainer else Color.Transparent,
		animationSpec = FloatSpec_Color,
		label = "navItemContainer",
	)
	val content by animateColorAsState(
		targetValue = if (selected) cs.primary else cs.onSurfaceVariant,
		animationSpec = FloatSpec_Color,
		label = "navItemContent",
	)
	val title = stringResource(item.titleRes)
	val interactionSource = remember { MutableInteractionSource() }

	Box(
		modifier = Modifier
			.height(48.dp)
			.background(color = container, shape = CircleShape)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = title
			},
		contentAlignment = Alignment.Center,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			BadgedBox(
				badge = {
					if (item.badgeCount > 0) {
						Badge { Text(text = if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
					} else if (item.badgeCount < 0) {
						Badge()
					}
				},
			) {
				// Use a real ImageView so the AnimatedStateListDrawable's enter/leave morphs
				// (avd_*_enter / avd_*_leave) actually tick. Painting an AVD onto Compose's
				// canvas via a custom Painter doesn't reliably drive the platform animator —
				// ImageView does, because it's the same path the native BottomNavigationView uses.
				NavIcon(
					resId = item.icon,
					selected = selected,
					tint = content,
					modifier = Modifier.size(24.dp),
				)
			}
			AnimatedVisibility(
				visible = selected && showLabel,
				enter = expandHorizontally(
					animationSpec = FloatSpec_Size,
					expandFrom = Alignment.Start,
				) + fadeIn(animationSpec = FloatSpec_Float),
				exit = shrinkHorizontally(
					animationSpec = FloatSpec_Size,
					shrinkTowards = Alignment.Start,
				) + fadeOut(animationSpec = FloatSpec_Float),
			) {
				Text(
					text = title,
					color = content,
					fontSize = 14.sp,
					lineHeight = 20.sp,
					maxLines = 1,
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}
	}
}

private val SELECTOR_STATE_CHECKED = intArrayOf(android.R.attr.state_checked)
private val SELECTOR_STATE_UNCHECKED = intArrayOf(-android.R.attr.state_checked)

/**
 * Renders a selector drawable (state-list with `<animated-selector>` transitions) inside
 * Compose by hosting a real [ImageView]. We use ImageView rather than a custom [Painter]
 * because `AnimatedVectorDrawable`'s animator pipeline doesn't reliably tick when painted
 * onto Compose's generic canvas — wrapping in a View matches the path the platform
 * `BottomNavigationView` uses, where these morphs are known to work.
 *
 * The [selected] flag drives the drawable state via [ImageView.setImageState], which is
 * what triggers the `<transition>` AVD between the normal and checked items.
 */
@Composable
private fun NavIcon(
	@DrawableRes resId: Int,
	selected: Boolean,
	tint: Color,
	modifier: Modifier = Modifier,
) {
	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			ImageView(ctx).apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setImageResource(resId)
				// Prime initial state without a transition. Setting state twice (empty,
				// then the real state) suppresses the first-paint morph that would
				// otherwise fire on inflate.
				setImageState(IntArray(0), false)
				jumpDrawablesToCurrentState()
			}
		},
		update = { iv ->
			if (iv.tag != resId) {
				iv.setImageResource(resId)
				iv.tag = resId
			}
			iv.imageTintList = ColorStateList.valueOf(tint.toArgb())
			val targetState = if (selected) SELECTOR_STATE_CHECKED else SELECTOR_STATE_UNCHECKED
			val current = iv.drawableState
			val isAlreadyChecked = current.any { it == android.R.attr.state_checked }
			if (isAlreadyChecked != selected) {
				iv.setImageState(targetState, false)
			}
		},
	)
}
