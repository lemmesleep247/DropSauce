package org.koitharu.kotatsu.settings.appearance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
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

	var uiMode by rememberStringPref(AppSettings.KEY_DETAILS_UI, DetailsUiMode.EXPRESSIVE.name)
	var backdrop by rememberBooleanPref(AppSettings.KEY_DETAILS_BACKDROP, true)
	var dynamicColor by rememberBooleanPref(AppSettings.KEY_DETAILS_DYNAMIC_COLOR, false)

	val mode = remember(uiMode) {
		DetailsUiMode.entries.firstOrNull { it.name == uiMode } ?: DetailsUiMode.EXPRESSIVE
	}

	SettingsScaffold(title = stringResource(R.string.details_appearance), onBack = onBack) {
		item {
			DetailsStylePreview(centered = mode != DetailsUiMode.COMPACT, backdropEnabled = backdrop)
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
					SwitchSettingsItem(
						title = stringResource(R.string.details_dynamic_color),
						subtitle = stringResource(R.string.details_dynamic_color_summary),
						checked = dynamicColor,
						onCheckedChange = { dynamicColor = it },
						icon = R.drawable.ic_appearance,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

/**
 * A small, theme-aware mock of the details page that reflects the selected layout: a centered cover
 * for "Centralized" or a side cover for "Compact", with an optional blurred-cover backdrop band.
 */
@Composable
private fun DetailsStylePreview(centered: Boolean, backdropEnabled: Boolean) {
	val scheme = MaterialTheme.colorScheme
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.height(216.dp),
		shape = RoundedCornerShape(20.dp),
		color = scheme.surface,
		border = BorderStroke(1.dp, scheme.outlineVariant),
	) {
		Box(Modifier.fillMaxSize()) {
			if (backdropEnabled) {
				Box(
					Modifier
						.fillMaxWidth()
						.fillMaxHeight(0.52f)
						.background(
							Brush.verticalGradient(
								listOf(scheme.primary.copy(alpha = 0.28f), Color.Transparent),
							),
						),
				)
			}
			if (centered) PreviewCentered(scheme) else PreviewCompact(scheme)
		}
	}
}

@Composable
private fun PreviewCentered(scheme: ColorScheme) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(top = 22.dp, start = 16.dp, end = 16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		PreviewCover(scheme, 50.dp, 72.dp)
		Spacer(Modifier.height(13.dp))
		PreviewBar(122.dp, 10.dp, scheme.onSurface.copy(alpha = 0.9f))
		Spacer(Modifier.height(7.dp))
		PreviewBar(82.dp, 7.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f))
		Spacer(Modifier.height(13.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			PreviewPill(42.dp, scheme)
			PreviewPill(30.dp, scheme)
			PreviewPill(48.dp, scheme)
		}
	}
}

@Composable
private fun PreviewCompact(scheme: ColorScheme) {
	Row(
		modifier = Modifier
			.fillMaxSize()
			.padding(top = 26.dp, start = 16.dp, end = 16.dp),
	) {
		PreviewCover(scheme, 58.dp, 84.dp)
		Spacer(Modifier.width(14.dp))
		Column(modifier = Modifier.padding(top = 6.dp)) {
			PreviewBar(132.dp, 11.dp, scheme.onSurface.copy(alpha = 0.9f))
			Spacer(Modifier.height(9.dp))
			PreviewBar(110.dp, 8.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f))
			Spacer(Modifier.height(6.dp))
			PreviewBar(96.dp, 8.dp, scheme.onSurfaceVariant.copy(alpha = 0.6f))
			Spacer(Modifier.height(14.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				PreviewPill(42.dp, scheme)
				PreviewPill(32.dp, scheme)
			}
		}
	}
}

@Composable
private fun PreviewCover(scheme: ColorScheme, width: Dp, height: Dp) {
	Box(
		Modifier
			.size(width, height)
			.clip(RoundedCornerShape(10.dp))
			.background(scheme.surfaceVariant),
	)
}

@Composable
private fun PreviewBar(width: Dp, height: Dp, color: Color) {
	Box(
		Modifier
			.size(width, height)
			.clip(RoundedCornerShape(50))
			.background(color),
	)
}

@Composable
private fun PreviewPill(width: Dp, scheme: ColorScheme) {
	Box(
		Modifier
			.size(width, 18.dp)
			.clip(RoundedCornerShape(50))
			.background(scheme.secondaryContainer),
	)
}
