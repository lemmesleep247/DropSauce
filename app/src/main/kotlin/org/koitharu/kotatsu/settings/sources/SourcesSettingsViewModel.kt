package org.koitharu.kotatsu.settings.sources

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class SourcesSettingsViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private val linksHandlerActivity = ComponentName(context, "org.koitharu.kotatsu.details.ui.DetailsByLinkActivity")

	val isLinksEnabled = MutableStateFlow(isLinksEnabled())

	fun setLinksEnabled(isEnabled: Boolean) {
		context.packageManager.setComponentEnabledSetting(
			linksHandlerActivity,
			if (isEnabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED,
			PackageManager.DONT_KILL_APP,
		)
		isLinksEnabled.value = isLinksEnabled()
	}

	private fun isLinksEnabled(): Boolean {
		val state = context.packageManager.getComponentEnabledSetting(linksHandlerActivity)
		return state == COMPONENT_ENABLED_STATE_ENABLED || state == COMPONENT_ENABLED_STATE_DEFAULT
	}
}
