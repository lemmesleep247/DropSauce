package org.koitharu.kotatsu.settings

import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.InfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.utils.RingtonePickContract
import javax.inject.Inject

@AndroidEntryPoint
class NotificationSettingsLegacyFragment :
	BaseComposeSettingsFragment(R.string.notifications) {

	@Inject
	lateinit var settings: AppSettings

	private val soundSummary = MutableStateFlow<String?>(null)

	private val ringtonePickContract = registerForActivityResult(
		RingtonePickContract(R.string.notification_sound),
	) { uri ->
		settings.notificationSound = uri ?: return@registerForActivityResult
		updateSoundSummary()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		updateSoundSummary()
		setContent {
			DropSauceTheme {
				NotificationsScreen(
					soundSummary = soundSummary.asStateFlow(),
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onPickSound = { ringtonePickContract.launch(settings.notificationSound) },
				)
			}
		}
	}

	private fun updateSoundSummary() {
		val ctx = context ?: return
		val uri: Uri? = settings.notificationSound
		soundSummary.value = RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx)
			?: getString(R.string.silent)
	}
}

@Composable
private fun NotificationsScreen(
	soundSummary: StateFlow<String?>,
	onBack: () -> Unit,
	onPickSound: () -> Unit,
) {
	val sound by soundSummary.collectAsState()
	var enabled by rememberBooleanPref(AppSettings.KEY_TRACKER_NOTIFICATIONS, true)
	var vibrate by rememberBooleanPref(AppSettings.KEY_NOTIFICATIONS_VIBRATE, false)
	var light by rememberBooleanPref(AppSettings.KEY_NOTIFICATIONS_LIGHT, true)

	SettingsScaffold(title = stringResource(R.string.notifications), onBack = onBack) {
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.notifications_enable),
						checked = enabled,
						onCheckedChange = { enabled = it },
						icon = R.drawable.ic_notification,
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.notification_sound),
						subtitle = sound,
						icon = R.drawable.ic_notification,
						shape = pos.shape,
						enabled = enabled,
						onClick = onPickSound,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.vibration),
						checked = vibrate,
						onCheckedChange = { vibrate = it },
						icon = R.drawable.ic_timelapse,
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.light_indicator),
						checked = light,
						onCheckedChange = { light = it },
						icon = R.drawable.ic_eye,
						shape = pos.shape,
						enabled = enabled,
					)
				}
			}
		}
		if (!enabled) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup {
					item { pos ->
						InfoSettingsItem(
							title = stringResource(R.string.show_notification_new_chapters_off),
							icon = R.drawable.ic_info_outline,
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
