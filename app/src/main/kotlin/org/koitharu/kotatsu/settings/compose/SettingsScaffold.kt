package org.koitharu.kotatsu.settings.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Top-level container for every redesigned settings screen.
 *
 * The activity's MaterialToolbar (set up by SettingsActivity / BaseActivity) is the single
 * top bar across the entire Settings flow — Compose screens and legacy
 * PreferenceFragmentCompat screens share it. The screen title is pushed to that toolbar
 * synchronously by [BaseComposeSettingsFragment.onResume]; this scaffold draws no top bar
 * of its own.
 *
 * Why no Compose top bar? Keeping the FragmentContainerView's bounds identical between
 * Compose and legacy fragments is the only reliable way to avoid mid-back-gesture layout
 * shifts and "snap" glitches when navigating between them.
 */
@Composable
fun SettingsScaffold(
	@Suppress("UNUSED_PARAMETER") title: String,
	@Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
	modifier: Modifier = Modifier,
	content: LazyListScope.() -> Unit,
) {
	// `title` and `onBack` are preserved in the signature for binary compat with existing
	// callers. Title is set via the BaseComposeSettingsFragment lifecycle hook, and the
	// activity's MaterialToolbar already provides the back button via setDisplayHomeAsUp.
	Box(
		modifier = modifier,
	) {
		LazyColumn(
			contentPadding = PaddingValues(
				top = 8.dp,
				bottom = 24.dp,
				start = 16.dp,
				end = 16.dp,
			),
			content = content,
		)
	}
}
