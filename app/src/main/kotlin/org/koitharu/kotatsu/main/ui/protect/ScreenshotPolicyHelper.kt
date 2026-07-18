package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import javax.inject.Inject

class ScreenshotPolicyHelper @Inject constructor(
	private val settings: AppSettings,
	private val protectHelper: AppProtectHelper,
) : DefaultActivityLifecycleCallbacks {

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		(activity as? ContentContainer)?.setupScreenshotPolicy(activity)
	}

	private fun ContentContainer.setupScreenshotPolicy(activity: Activity) =
		lifecycleScope.launch(Dispatchers.Main.immediate) {
			val screenshotPolicyFlow = settings.observeAsFlow(AppSettings.KEY_SCREENSHOTS_POLICY) { screenshotsPolicy }
				.flatMapLatest { policy ->
					when (policy) {
						ScreenshotsPolicy.ALLOW -> flowOf(false)
						ScreenshotsPolicy.BLOCK_NSFW -> isNsfwContent().distinctUntilChanged()

						ScreenshotsPolicy.BLOCK_ALL -> flowOf(true)
						ScreenshotsPolicy.BLOCK_INCOGNITO -> settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) {
							isIncognitoModeEnabled
						}
					}
				}

			val protectAppFlow = settings.observeAsFlow(AppSettings.KEY_PROTECT_APP) { isAppProtectionEnabled }

			combine(
				screenshotPolicyFlow,
				protectAppFlow,
				protectHelper.isUnlockedFlow,
			) { screenshotSecure, protectEnabled, isUnlocked ->
				screenshotSecure || (protectEnabled && !isUnlocked)
			}.collect { isSecure ->
				if (isSecure) {
					activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
				} else {
					activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
				}
			}
		}

	interface ContentContainer : LifecycleOwner {

		@MainThread
		fun isNsfwContent(): Flow<Boolean>
	}
}
