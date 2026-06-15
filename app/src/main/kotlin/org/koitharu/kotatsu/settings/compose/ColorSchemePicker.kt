package org.koitharu.kotatsu.settings.compose

import android.content.Context
import android.content.res.TypedArray
import android.util.TypedValue
import androidx.appcompat.R as appcompatR
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ColorScheme
import com.google.android.material.R as materialR

/**
 * Inline horizontal color-scheme picker that mirrors the legacy ThemeChooserPreference:
 * each entry is a small themed preview card (with a tiny "Abc" + primary/secondary swatches),
 * tap-to-select, check mark on the active one.
 *
 * Lives inline within the AppearanceScreen (not a dialog) — matching the legacy widget's
 * placement on the page.
 */
@Composable
fun ColorSchemePickerRow(
	title: String,
	selectedValue: String,
	onValueChange: (String) -> Unit,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	val context = LocalContext.current
	val schemes = remember { ColorScheme.getAvailableList() }
	val previews = remember(schemes) {
		schemes.map { it to resolveSchemeColors(context, it.styleResId) }
	}

	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(
			modifier = Modifier.padding(vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				modifier = Modifier.padding(horizontal = 16.dp),
			)
			LazyRow(
				contentPadding = PaddingValues(horizontal = 12.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				items(previews, key = { it.first.name }) { (scheme, colors) ->
					ColorSchemeCard(
						scheme = scheme,
						colors = colors,
						selected = scheme.name == selectedValue,
						enabled = enabled,
						onClick = {
							onValueChange(scheme.name)
						},
					)
				}
			}
		}
	}
}

@Composable
private fun ColorSchemeCard(
	scheme: ColorScheme,
	colors: SchemePreviewColors,
	selected: Boolean,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val cardShape = RoundedCornerShape(16.dp)
	val border = if (selected) {
		BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
	} else {
		BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
	}
	Column(
		modifier = Modifier
			.width(96.dp)
			.clickable(enabled = enabled, onClick = onClick),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Surface(
			modifier = Modifier.size(width = 88.dp, height = 110.dp),
			shape = cardShape,
			color = colors.surface,
			border = border,
		) {
			Box(modifier = Modifier.padding(8.dp)) {
				Text(
					text = "Abc",
					color = colors.onSurface,
					style = MaterialTheme.typography.titleSmall,
					modifier = Modifier.align(Alignment.TopStart),
				)
				Column(
					modifier = Modifier.align(Alignment.BottomStart),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Box(
						modifier = Modifier
							.fillMaxWidth(0.4f)
							.height(6.dp)
							.background(colors.secondary, RoundedCornerShape(4.dp)),
					)
					Box(
						modifier = Modifier
							.fillMaxWidth(0.7f)
							.height(6.dp)
							.background(colors.secondary, RoundedCornerShape(4.dp)),
					)
				}
				Box(
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.size(16.dp)
						.background(colors.primary, RoundedCornerShape(6.dp)),
				)
				if (selected) {
					Icon(
						imageVector = Icons.Filled.Check,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.primary,
						modifier = Modifier
							.align(Alignment.TopEnd)
							.size(18.dp),
					)
				}
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(
			text = stringResource(scheme.titleResId),
			style = MaterialTheme.typography.labelSmall,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
			maxLines = 1,
			color = if (enabled) MaterialTheme.colorScheme.onSurface
			else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
		)
	}
}

@Immutable
private data class SchemePreviewColors(
	val primary: Color,
	val secondary: Color,
	val surface: Color,
	val onSurface: Color,
)

/** Resolve a ColorScheme's primary/secondary/surface colors by inflating its theme overlay. */
@android.annotation.SuppressLint("ResourceType")
private fun resolveSchemeColors(context: Context, styleResId: Int): SchemePreviewColors {
	val themed = ContextThemeWrapper(context, styleResId)
	val attrs = intArrayOf(
		appcompatR.attr.colorPrimary,
		materialR.attr.colorSecondary,
		materialR.attr.colorSurfaceContainer,
		materialR.attr.colorOnSurface,
	)
	val ta: TypedArray = themed.obtainStyledAttributes(attrs)
	try {
		val primary = ta.getColor(0, 0xFF000000.toInt())
		val secondary = ta.getColor(1, primary)
		val surface = ta.getColor(2, 0xFFFFFFFF.toInt())
		val onSurface = ta.getColor(3, 0xFF000000.toInt())
		return SchemePreviewColors(
			primary = Color(primary),
			secondary = Color(secondary),
			surface = Color(surface),
			onSurface = Color(onSurface),
		)
	} finally {
		ta.recycle()
	}
}
