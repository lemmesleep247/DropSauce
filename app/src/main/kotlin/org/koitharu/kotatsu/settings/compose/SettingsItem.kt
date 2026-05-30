package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Generic row in a [SettingsGroup]. Lays out a circular tinted icon, a 1- or 2-line text
 * block, and an optional trailing slot (switch, value chip, chevron, etc.).
 *
 * Designed to be the building block for typed wrappers like [SwitchSettingsItem],
 * [ListSettingsItem], etc. — keep the bespoke logic in those wrappers and just delegate
 * the rendering here.
 */
@Composable
fun SettingsItem(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	onClick: (() -> Unit)? = null,
	trailing: @Composable (() -> Unit)? = null,
) {
	// One-shot search highlight: scroll this row comfortably into view and flash its background
	// once when it is the navigation target (matched by title).
	val pendingHighlight by SettingsSearchHighlight.pendingTitle.collectAsState()
	val isHighlightTarget = pendingHighlight != null && pendingHighlight == title
	val scrollToHighlight = LocalSettingsHighlightScroll.current
	val rowWindowY = remember { mutableFloatStateOf(Float.NaN) }
	val highlight = remember { Animatable(0f) }
	LaunchedEffect(isHighlightTarget) {
		if (isHighlightTarget) {
			// Wait until this row has a real laid-out position, then scroll to it.
			val y = snapshotFlow { rowWindowY.floatValue }.first { !it.isNaN() }
			scrollToHighlight(y)
			highlight.snapTo(1f)
			delay(320)
			highlight.animateTo(0f, animationSpec = tween(durationMillis = 1100))
			SettingsSearchHighlight.consume(title)
		}
	}
	val containerColor = lerp(
		MaterialTheme.colorScheme.surfaceContainer,
		MaterialTheme.colorScheme.primaryContainer,
		highlight.value,
	)
	// Pin the content color: an interpolated `color` makes contentColorFor() return Unspecified,
	// which would render text as plain black (broken in dark mode). Keep text legible on both
	// the resting surfaceContainer and the brief primaryContainer flash.
	val contentColor = lerp(
		MaterialTheme.colorScheme.onSurface,
		MaterialTheme.colorScheme.onPrimaryContainer,
		highlight.value,
	)
	Surface(
		modifier = modifier.onGloballyPositioned { rowWindowY.floatValue = it.positionInWindow().y },
		shape = shape,
		color = containerColor,
		contentColor = contentColor,
	) {
		Row(
			modifier = Modifier
				.heightIn(min = 72.dp)
				.let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (icon != null) {
				if (iconColors != null) {
					SettingsIconBubble(
						iconRes = icon,
						colors = iconColors,
						enabled = enabled,
					)
				} else {
					SettingsIconPlain(iconRes = icon, enabled = enabled)
				}
				Spacer(Modifier.width(14.dp))
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					color = textColor(enabled),
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (!subtitle.isNullOrBlank()) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = secondaryTextColor(enabled),
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			if (trailing != null) {
				Spacer(Modifier.width(8.dp))
				trailing()
			}
		}
	}
}

/** Convenience for a SwitchPreference — bool state + listener. */
@Composable
fun SwitchSettingsItem(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = if (enabled) {
			{ onCheckedChange(!checked) }
		} else null,
		trailing = {
			Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
		},
	)
}

/** A row whose trailing slot shows the current value text (typical of ListPreference). */
@Composable
fun ValueSettingsItem(
	title: String,
	value: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle ?: value,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = onClick,
	)
}

@Composable
private fun SettingsIconBubble(
	@DrawableRes iconRes: Int,
	colors: CategoryIconColors,
	enabled: Boolean,
) {
	val containerAlpha = if (enabled) 1f else 0.4f
	val contentAlpha = if (enabled) 1f else 0.5f
	Box(
		modifier = Modifier
			.size(44.dp)
			.clip(CircleShape)
			.background(colors.container.copy(alpha = containerAlpha)),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(22.dp),
			colorFilter = ColorFilter.tint(colors.onContainer.copy(alpha = contentAlpha)),
		)
	}
}

@Composable
private fun SettingsIconPlain(@DrawableRes iconRes: Int, enabled: Boolean) {
	val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f)
	Box(
		modifier = Modifier.size(44.dp),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(24.dp),
			colorFilter = ColorFilter.tint(tint),
		)
	}
}

@Composable
private fun textColor(enabled: Boolean): Color {
	val base = LocalContentColor.current
	return if (enabled) base else base.copy(alpha = 0.38f)
}

@Composable
private fun secondaryTextColor(enabled: Boolean): Color {
	val base = MaterialTheme.colorScheme.onSurfaceVariant
	return if (enabled) base else base.copy(alpha = 0.38f)
}
