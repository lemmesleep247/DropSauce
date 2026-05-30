package org.koitharu.kotatsu.settings.appearance

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.transition.TransitionManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.details.ui.BackdropController.Companion.blurRadius
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@AndroidEntryPoint
class PreviewSettingsFragment : BaseComposeSettingsFragment(R.string.details_appearance) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				DetailsAppearanceScreen(
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
				)
			}
		}
	}
}

@Composable
private fun DetailsAppearanceScreen(onBack: () -> Unit) {
	val ctx = LocalContext.current
	val uiModeEntries = remember { ctx.resources.getStringArray(R.array.details_ui).toList() }
	val uiModeValues = remember { DetailsUiMode.entries.names().toList() }

	var uiMode by rememberStringPref(AppSettings.KEY_DETAILS_UI, DetailsUiMode.MODERN.name)
	var backdrop by rememberBooleanPref(AppSettings.KEY_DETAILS_BACKDROP, true)
	var blur by rememberIntPref(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT, 60)

	val mode = remember(uiMode) {
		DetailsUiMode.entries.firstOrNull { it.name == uiMode } ?: DetailsUiMode.MODERN
	}

	SettingsScaffold(title = stringResource(R.string.details_appearance), onBack = onBack) {
		item {
			AndroidView(
				modifier = Modifier.fillMaxWidth(),
				factory = { c ->
					LayoutInflater.from(c).inflate(R.layout.preference_details_preview, null, false)
				},
				update = { root -> applyPreview(root, mode, backdrop, blur) },
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.details_ui)) {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.details_ui),
						entries = uiModeEntries,
						entryValues = uiModeValues,
						selectedValue = uiMode,
						onValueChange = { uiMode = it },
						icon = R.drawable.ic_appearance,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.details_backdrop)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.details_backdrop),
						subtitle = stringResource(R.string.details_backdrop_summary),
						checked = backdrop,
						onCheckedChange = { backdrop = it },
						icon = R.drawable.ic_images,
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.details_backdrop_blur),
						value = blur.coerceIn(0, 100),
						valueFrom = 0,
						valueTo = 100,
						stepSize = 5,
						unitSuffix = "%",
						onValueChange = { blur = it },
						icon = R.drawable.ic_auto_fix,
						shape = pos.shape,
						enabled = backdrop,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

// region Live preview rendering (ported from the legacy PreviewSettingsPreference)

private const val MAX_PREVIEW_BLUR_RADIUS = 20f

@Suppress("DEPRECATION")
private fun applyPreview(root: View, mode: DetailsUiMode, backdropOn: Boolean, blur: Int) {
	val backdropView = root.findViewById<ImageView>(R.id.preview_backdrop)
	val gradientView = root.findViewById<View>(R.id.preview_gradient)
	val titleBar = root.findViewById<View>(R.id.preview_title)
	val subtitleBar = root.findViewById<View>(R.id.preview_subtitle)
	val infoColumn = root.findViewById<LinearLayout>(R.id.preview_info_column)

	val bgColor = com.google.android.material.color.MaterialColors.getColor(
		root, com.google.android.material.R.attr.colorSurface, Color.WHITE,
	)
	val fgColor = obtainAttrColor(root.context, android.R.attr.colorForeground, Color.DKGRAY)

	gradientView.background = GradientDrawable(
		GradientDrawable.Orientation.TOP_BOTTOM,
		intArrayOf(
			Color.TRANSPARENT,
			ColorUtils.setAlphaComponent(bgColor, 30),
			ColorUtils.setAlphaComponent(bgColor, 140),
			bgColor,
		),
	)
	titleBar.background = roundedBar(fgColor, 0xCC)
	subtitleBar.background = roundedBar(fgColor, 0x88)

	backdropView.visibility = if (backdropOn) View.VISIBLE else View.INVISIBLE
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		backdropView.setRenderEffect(
			if (blur <= 0) {
				null
			} else {
				android.graphics.RenderEffect.createBlurEffect(
					blurRadius(blur, MAX_PREVIEW_BLUR_RADIUS),
					blurRadius(blur, MAX_PREVIEW_BLUR_RADIUS),
					android.graphics.Shader.TileMode.CLAMP,
				)
			},
		)
	} else {
		backdropView.alpha = if (blur <= 0) 0.9f else 0.5f + (1f - blur / 100f) * 0.4f
	}

	val lastMode = infoColumn.getTag(R.id.preview_info_column) as? DetailsUiMode
	if (lastMode != mode) {
		infoColumn.setTag(R.id.preview_info_column, mode)
		(infoColumn.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it) }
		infoColumn.removeAllViews()
		when (mode) {
			DetailsUiMode.CLASSIC -> buildClassicContent(infoColumn, fgColor)
			DetailsUiMode.MODERN -> buildModernContent(infoColumn, bgColor, fgColor)
		}
	}
}

private fun buildClassicContent(parent: LinearLayout, fgColor: Int) {
	val ctx = parent.context
	val gap = ctx.dp(6)
	val chipH = ctx.dp(22)
	val rows = listOf(
		listOf(ctx.dp(80), ctx.dp(54), ctx.dp(60)),
		listOf(ctx.dp(50), ctx.dp(66)),
	)
	rows.forEachIndexed { ri, widths ->
		val row = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).also { if (ri > 0) it.topMargin = gap }
		}
		widths.forEachIndexed { ci, w ->
			row.addView(View(ctx).apply {
				layoutParams = LinearLayout.LayoutParams(w, chipH).also {
					if (ci > 0) it.marginStart = gap
				}
				background = GradientDrawable().apply {
					shape = GradientDrawable.RECTANGLE
					cornerRadius = chipH / 2f
					setColor(ColorUtils.setAlphaComponent(fgColor, 0x22))
					setStroke(ctx.dp(1), ColorUtils.setAlphaComponent(fgColor, 0x55))
				}
			})
		}
		parent.addView(row)
	}
}

private fun buildModernContent(parent: LinearLayout, bgColor: Int, fgColor: Int) {
	val ctx = parent.context
	val card = LinearLayout(ctx).apply {
		orientation = LinearLayout.VERTICAL
		background = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = ctx.dp(10).toFloat()
			setColor(ColorUtils.setAlphaComponent(bgColor, 60))
			setStroke(1, ColorUtils.setAlphaComponent(fgColor, 40))
		}
		layoutParams = LinearLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		)
		setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(6))
	}

	val labelWidth = ctx.dp(44)
	val valueWidths = listOf(ctx.dp(72), ctx.dp(56), ctx.dp(44), ctx.dp(36))
	valueWidths.forEachIndexed { i, valWidth ->
		val row = LinearLayout(ctx).apply {
			orientation = LinearLayout.HORIZONTAL
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			).also { if (i > 0) it.topMargin = ctx.dp(4) }
		}
		row.addView(View(ctx).apply {
			layoutParams = LinearLayout.LayoutParams(labelWidth, ctx.dp(6))
			background = roundedBar(fgColor, 0x70)
		})
		row.addView(View(ctx).apply {
			layoutParams = LinearLayout.LayoutParams(valWidth, ctx.dp(6)).also {
				it.marginStart = ctx.dp(6)
			}
			background = roundedBar(fgColor, 0xAA)
		})
		card.addView(row)
	}
	parent.addView(card)
}

private fun roundedBar(color: Int, alpha: Int): GradientDrawable =
	GradientDrawable().apply {
		shape = GradientDrawable.RECTANGLE
		cornerRadius = 100f
		setColor(ColorUtils.setAlphaComponent(color, alpha))
	}

private fun obtainAttrColor(context: Context, attr: Int, default: Int): Int =
	runCatching {
		context.obtainStyledAttributes(intArrayOf(attr)).run {
			getColor(0, default).also { recycle() }
		}
	}.getOrDefault(default)

private fun Context.dp(value: Int): Int =
	TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
	).toInt()

// endregion
