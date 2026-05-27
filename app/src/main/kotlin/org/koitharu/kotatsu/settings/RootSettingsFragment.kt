package org.koitharu.kotatsu.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.koitharu.kotatsu.settings.compose.ComposeOwnedScreen
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.about.AboutSettingsFragment
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.sources.ExtensionsSettingsFragment
import org.koitharu.kotatsu.settings.tracker.TrackerSettingsFragment

/**
 * Redesigned settings landing screen — Compose-based, modeled after PixelPlayer's settings.
 * Uses M3 LargeTopAppBar (collapses on scroll) and a single rounded SettingsGroup with the
 * dynamic-corner-radius pattern for the 9 section cards.
 *
 * Hides the host SettingsActivity's MaterialToolbar while attached, since we draw our own
 * top bar inside Compose. Sub-screens still use the activity's toolbar.
 */
@AndroidEntryPoint
class RootSettingsFragment : BaseComposeSettingsFragment(R.string.settings) {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				RootSettingsContent(
					appVersion = BuildConfig.VERSION_NAME,
					onSectionClick = { section -> openSection(section) },
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
	}

	private fun openSection(section: SettingsSection) {
		val activity = activity as? SettingsActivity ?: return
		activity.openFragment(section.fragmentClass, null, isFromRoot = true)
	}

	companion object {
		// Required because SettingsActivity reads this on a tablet master pane.
		@Suppress("unused")
		fun newInstance(context: Context): RootSettingsFragment = RootSettingsFragment()
	}
}

private enum class SettingsSection(
	val titleRes: Int,
	val iconRes: Int,
	val paletteKey: String,
	val summaryRes: IntArray,
	val fragmentClass: Class<out Fragment>,
) {
	APPEARANCE(
		R.string.appearance, R.drawable.ic_appearance, "appearance",
		intArrayOf(R.string.theme, R.string.list_mode, R.string.language),
		AppearanceSettingsFragment::class.java,
	),
	EXTENSIONS(
		R.string.extensions, R.drawable.ic_manga_source, "extensions",
		intArrayOf(R.string.manage_extensions, R.string.nsfw_filter, R.string.sort_order),
		ExtensionsSettingsFragment::class.java,
	),
	READER(
		R.string.reader_settings, R.drawable.ic_book_page, "reader",
		intArrayOf(R.string.read_mode, R.string.scale_mode, R.string.switch_pages),
		ReaderSettingsFragment::class.java,
	),
	STORAGE(
		R.string.storage_and_network, R.drawable.ic_usage, "storage",
		intArrayOf(R.string.storage_usage, R.string.proxy, R.string.prefetch_content),
		StorageAndNetworkSettingsFragment::class.java,
	),
	DOWNLOADS(
		R.string.downloads, R.drawable.ic_download, "downloads",
		intArrayOf(R.string.manga_save_location, R.string.downloads_wifi_only),
		DownloadsSettingsFragment::class.java,
	),
	BACKUP(
		R.string.backup_restore, R.drawable.ic_backup_restore, "backup",
		intArrayOf(R.string.restore_backup),
		BackupSettingsFragment::class.java,
	),
	TRACKER(
		R.string.check_for_new_chapters, R.drawable.ic_feed, "tracker",
		intArrayOf(R.string.track_sources, R.string.notifications_settings),
		TrackerSettingsFragment::class.java,
	),
	SERVICES(
		R.string.services, R.drawable.ic_services, "services",
		intArrayOf(R.string.suggestions, R.string.tracking),
		ServicesSettingsFragment::class.java,
	),
	ABOUT(
		R.string.about, R.drawable.ic_info_outline, "about",
		IntArray(0),
		AboutSettingsFragment::class.java,
	),
}

@Composable
private fun RootSettingsContent(
	appVersion: String,
	onSectionClick: (SettingsSection) -> Unit,
	onBack: () -> Unit,
) {
	val ctx = LocalContext.current
	SettingsScaffold(
		title = stringResource(R.string.settings),
		onBack = onBack,
	) {
		item {
			SettingsGroup {
				// SettingsGroup's DSL block is NOT @Composable — @Composable calls
				// (stringResource, MaterialTheme.colorScheme, …) only happen inside
				// each `item { pos -> ... }` body which IS @Composable.
				SettingsSection.values().forEach { section ->
					item { pos ->
						val subtitle = if (section == SettingsSection.ABOUT) {
							appVersion
						} else {
							section.summaryRes.joinToString { ctx.getString(it) }
						}
						SettingsItem(
							title = stringResource(section.titleRes),
							subtitle = subtitle,
							icon = section.iconRes,
							iconColors = CategoryPalette.forKey(section.paletteKey),
							shape = pos.shape,
							onClick = { onSectionClick(section) },
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
