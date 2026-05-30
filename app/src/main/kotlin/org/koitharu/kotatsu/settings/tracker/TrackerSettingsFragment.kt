package org.koitharu.kotatsu.settings.tracker

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.TrackerDownloadStrategy
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.ComposeOwnedScreen
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref
import org.koitharu.kotatsu.tracker.ui.debug.TrackerDebugActivity
import org.koitharu.kotatsu.tracker.work.TrackerNotificationHelper
import javax.inject.Inject

@AndroidEntryPoint
class TrackerSettingsFragment : BaseComposeSettingsFragment(R.string.check_for_new_chapters) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var notificationHelper: TrackerNotificationHelper

	private val viewModel by viewModels<TrackerSettingsViewModel>()
	private val notificationsEnabledState = MutableStateFlow(false)
	private val categoriesCountState = MutableStateFlow<IntArray?>(null)
	private val dozeAvailableState = MutableStateFlow(false)

	private val startForDozeResult = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		dozeAvailableState.value = isDozeIgnoreAvailable()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val notificationsEnabled by notificationsEnabledState.asStateFlow().collectAsState()
				val categoriesCount by categoriesCountState.asStateFlow().collectAsState()
				val dozeAvailable by dozeAvailableState.asStateFlow().collectAsState()
				TrackerScreen(
					notificationsEnabled = notificationsEnabled,
					categoriesCount = categoriesCount,
					dozeAvailable = dozeAvailable,
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onTrackCategories = router::showTrackerCategoriesConfigSheet,
					onNotificationsSettings = ::openNotificationsSettings,
					onOpenLegacyNotifications = {
						(activity as? SettingsActivity)?.openFragment(
							NotificationSettingsLegacyFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onTrackerDebug = {
						startActivity(Intent(requireContext(), TrackerDebugActivity::class.java))
					},
					onIgnoreDoze = ::startIgnoreDoseActivity,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.categoriesCount.observe(viewLifecycleOwner) { categoriesCountState.value = it }
	}

	override fun onResume() {
		super.onResume()
		notificationsEnabledState.value = notificationHelper.getAreNotificationsEnabled()
		dozeAvailableState.value = isDozeIgnoreAvailable()
	}

	private fun openNotificationsSettings() {
		val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
				.putExtra(SystemSettings.EXTRA_APP_PACKAGE, requireContext().packageName)
		} else if (!notificationHelper.getAreNotificationsEnabled()) {
			Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
				.setData(Uri.fromParts("package", requireContext().packageName, null))
		} else {
			// Fall through to the legacy in-app notification settings screen.
			(activity as? SettingsActivity)?.openFragment(
				NotificationSettingsLegacyFragment::class.java,
				null,
				isFromRoot = false,
			)
			return
		}
		try {
			startActivity(intent)
		} catch (_: ActivityNotFoundException) {
			Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}

	private fun isDozeIgnoreAvailable(): Boolean {
		val ctx = context ?: return false
		val pm = ctx.powerManager ?: return false
		return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
	}

	@SuppressLint("BatteryLife")
	private fun startIgnoreDoseActivity() {
		val ctx = context ?: return
		val pm = ctx.powerManager ?: return
		if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
		try {
			val intent = Intent(
				SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
				"package:${ctx.packageName}".toUri(),
			)
			startForDozeResult.launch(intent)
		} catch (_: ActivityNotFoundException) {
			Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}
}

@Composable
private fun TrackerScreen(
	notificationsEnabled: Boolean,
	categoriesCount: IntArray?,
	dozeAvailable: Boolean,
	onBack: () -> Unit,
	onTrackCategories: () -> Unit,
	onNotificationsSettings: () -> Unit,
	onOpenLegacyNotifications: () -> Unit,
	onTrackerDebug: () -> Unit,
	onIgnoreDoze: () -> Unit,
) {
	val ctx = LocalContext.current
	val colors = CategoryPalette.forKey("tracker")

	var enabled by rememberBooleanPref(AppSettings.KEY_TRACKER_ENABLED, true)
	var wifiOnly by rememberBooleanPref(AppSettings.KEY_TRACKER_WIFI_ONLY, false)
	var frequency by rememberStringPref(AppSettings.KEY_TRACKER_FREQUENCY, "1")
	var trackSources by rememberStringSetPref(AppSettings.KEY_TRACK_SOURCES, setOf(AppSettings.TRACK_FAVOURITES))
	var trackerNoNsfw by rememberBooleanPref(AppSettings.KEY_TRACKER_NO_NSFW, false)
	var downloadStrategy by rememberStringPref(
		AppSettings.KEY_TRACKER_DOWNLOAD,
		TrackerDownloadStrategy.DISABLED.name,
	)

	val freqEntries = remember { ctx.resources.getStringArray(R.array.tracker_frequency).toList() }
	val freqValues = remember { ctx.resources.getStringArray(R.array.values_tracker_frequency).toList() }
	val sourceEntries = remember { ctx.resources.getStringArray(R.array.track_sources).toList() }
	val sourceValues = remember { ctx.resources.getStringArray(R.array.values_track_sources).toList() }
	val downloadEntries = remember {
		ctx.resources.getStringArray(R.array.tracker_download_strategies).toList()
	}
	val downloadValues = remember { TrackerDownloadStrategy.entries.names().toList() }

	val notificationsSummary = if (notificationsEnabled) {
		stringResource(R.string.show_notification_new_chapters_on)
	} else {
		stringResource(R.string.show_notification_new_chapters_off)
	}
	val categoriesSummary = categoriesCount?.let {
		ctx.getString(R.string.enabled_d_of_d, it[0], it[1])
	}
	val categoriesEnabled = enabled && AppSettings.TRACK_FAVOURITES in trackSources

	SettingsScaffold(title = stringResource(R.string.check_for_new_chapters), onBack = onBack) {
		// Master toggle gets its own standalone group at the top, separated from the rest.
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.check_new_chapters_title),
						checked = enabled,
						onCheckedChange = { enabled = it },
						icon = R.drawable.ic_feed,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Tracker") {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.only_using_wifi),
						subtitle = stringResource(R.string.tracker_wifi_only_summary),
						checked = wifiOnly,
						onCheckedChange = { wifiOnly = it },
						icon = R.drawable.ic_network_cellular,
						
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.frequency_of_check),
						entries = freqEntries,
						entryValues = freqValues,
						selectedValue = frequency,
						onValueChange = { frequency = it },
						icon = R.drawable.ic_timelapse,
						
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					MultiSelectSettingsItem(
						title = stringResource(R.string.track_sources),
						entries = sourceEntries,
						entryValues = sourceValues,
						selectedValues = trackSources,
						onValuesChange = { trackSources = it },
						icon = R.drawable.ic_manga_source,
						
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.favourites_categories),
						subtitle = categoriesSummary,
						icon = R.drawable.ic_list_group,
						
						shape = pos.shape,
						enabled = categoriesEnabled,
						onClick = onTrackCategories,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.notifications_settings),
						subtitle = notificationsSummary,
						icon = R.drawable.ic_notification,
						
						shape = pos.shape,
						enabled = enabled,
						onClick = onNotificationsSettings,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.notifications),
						icon = R.drawable.ic_notification,
						
						shape = pos.shape,
						enabled = enabled,
						onClick = onOpenLegacyNotifications,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_nsfw_notifications),
						subtitle = stringResource(R.string.disable_nsfw_notifications_summary),
						checked = trackerNoNsfw,
						onCheckedChange = { trackerNoNsfw = it },
						icon = R.drawable.ic_nsfw,
						
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.download_new_chapters),
						entries = downloadEntries,
						entryValues = downloadValues,
						selectedValue = downloadStrategy,
						onValueChange = { downloadStrategy = it },
						icon = R.drawable.ic_download,
						
						shape = pos.shape,
						enabled = enabled,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.debug)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.tracker_debug_info),
						subtitle = stringResource(R.string.tracker_debug_info_summary),
						icon = R.drawable.ic_script,
						
						shape = pos.shape,
						enabled = enabled,
						onClick = onTrackerDebug,
					)
				}
				if (dozeAvailable) {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.disable_battery_optimization),
							subtitle = stringResource(R.string.disable_battery_optimization_summary),
							icon = R.drawable.ic_battery_outline,
							
							shape = pos.shape,
							enabled = enabled,
							onClick = onIgnoreDoze,
						)
					}
				}
				item { pos ->
					InfoSettingsItem(
						title = stringResource(R.string.tracker_warning),
						subtitle = "dontkillmyapp.com",
						icon = R.drawable.ic_info_outline,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
