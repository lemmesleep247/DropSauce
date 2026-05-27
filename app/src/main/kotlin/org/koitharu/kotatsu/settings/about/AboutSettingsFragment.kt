package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.ComposeOwnedScreen
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold

@AndroidEntryPoint
class AboutSettingsFragment : BaseComposeSettingsFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val isUpdateSupported by viewModel.isUpdateSupported.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				AboutScreen(
					appVersion = BuildConfig.VERSION_NAME,
					checkUpdatesEnabled = isUpdateSupported && !isLoading,
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onCheckUpdates = viewModel::checkForUpdates,
					onChangelog = ::openChangelog,
					onOpenLink = ::openLink,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
	}

	private fun openChangelog() {
		(activity as? SettingsActivity)?.openFragment(
			ChangelogFragment::class.java,
			null,
			isFromRoot = false,
		)
	}

	private fun openLink(@StringRes urlRes: Int, titleRes: Int) {
		val opened = router.openExternalBrowser(getString(urlRes), getString(titleRes))
		if (!opened) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(requireView(), R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}
}

@Composable
private fun AboutScreen(
	appVersion: String,
	checkUpdatesEnabled: Boolean,
	onBack: () -> Unit,
	onCheckUpdates: () -> Unit,
	onChangelog: () -> Unit,
	onOpenLink: (urlRes: Int, titleRes: Int) -> Unit,
) {
	val ctx = LocalContext.current
	SettingsScaffold(title = stringResource(R.string.about), onBack = onBack) {
		// Hero header: app icon + name + version chip — gives the About screen a sense of place
		// instead of being just another list of links.
		item { AboutHero(appVersion = appVersion) }
		item { Spacer(Modifier.height(16.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Updates") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.check_for_updates),
						subtitle = ctx.getString(R.string.app_version, appVersion),
						icon = R.drawable.ic_app_update,
						shape = pos.shape,
						enabled = checkUpdatesEnabled,
						onClick = onCheckUpdates,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.changelog),
						subtitle = stringResource(R.string.changelog_summary),
						icon = R.drawable.ic_history,
						shape = pos.shape,
						onClick = onChangelog,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Links") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.user_manual),
						subtitle = stringResource(R.string.url_user_manual),
						icon = R.drawable.ic_book_page,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_user_manual, R.string.user_manual) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.source_code),
						subtitle = stringResource(R.string.url_github),
						icon = R.drawable.ic_open_external,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_github, R.string.source_code) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.about_app_translation_summary),
						subtitle = stringResource(R.string.url_weblate),
						icon = R.drawable.ic_language,
						shape = pos.shape,
						onClick = {
							onOpenLink(R.string.url_weblate, R.string.about_app_translation_summary)
						},
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.discord),
						subtitle = stringResource(R.string.url_discord_web),
						icon = R.drawable.ic_discord,
						shape = pos.shape,
						onClick = { onOpenLink(R.string.url_discord_web, R.string.discord) },
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@Composable
private fun AboutHero(appVersion: String) {
	val cs = MaterialTheme.colorScheme
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(24.dp),
		color = cs.primaryContainer,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 32.dp, horizontal = 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Box(
				modifier = Modifier
					.size(96.dp)
					.background(cs.onPrimaryContainer.copy(alpha = 0.12f), CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Image(
					painter = rememberAnyDrawablePainter(org.koitharu.kotatsu.R.mipmap.ic_launcher),
					contentDescription = null,
					modifier = Modifier.size(72.dp),
				)
			}
			Text(
				text = stringResource(R.string.app_name),
				style = MaterialTheme.typography.headlineMedium,
				color = cs.onPrimaryContainer,
				fontWeight = FontWeight.SemiBold,
			)
			Surface(
				shape = RoundedCornerShape(50),
				color = cs.onPrimaryContainer.copy(alpha = 0.18f),
			) {
				Text(
					text = "v$appVersion",
					style = MaterialTheme.typography.labelMedium,
					color = cs.onPrimaryContainer,
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
				)
			}
		}
	}
}
