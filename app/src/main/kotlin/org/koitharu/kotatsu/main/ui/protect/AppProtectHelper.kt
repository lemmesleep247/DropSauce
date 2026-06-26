package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import org.acra.dialog.CrashReportDialog
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppProtectHelper @Inject constructor(
	private val settings: AppSettings,
	private val recreationHandle: ActivityRecreationHandle,
) : DefaultActivityLifecycleCallbacks {

	private val _isUnlocked = MutableStateFlow(!settings.isAppProtectionEnabled)
	val isUnlockedFlow = _isUnlocked.asStateFlow()

	private var isUnlocked: Boolean
		get() = _isUnlocked.value
		set(value) {
			_isUnlocked.value = value
		}

	private var startedActivities = 0
	private var lastBackgroundAt = 0L
	/** True while a [ProtectActivity] is already in the foreground to prevent stacking. */
	private var isProtectScreenShowing = false

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		if (!ensureProtectionAvailability(activity)) {
			return
		}
		if (!settings.isAppProtectionEnabled) {
			isUnlocked = true
			return
		}
		updateActivityVisibility(activity)
		if (!isUnlocked && activity !is CrashReportDialog) {
			showProtectScreen(activity)
		}
	}

	override fun onActivityStarted(activity: Activity) {
		if (!ensureProtectionAvailability(activity)) {
			return
		}
		if (activity is ProtectActivity || !settings.isAppProtectionEnabled) {
			return
		}
		// Skip lock-state evaluation entirely while a theme/colour recreation is in flight.
		// During recreation the activity briefly goes through stop→start, which would
		// otherwise look like an app-backgrounding event and falsely re-lock.
		if (recreationHandle.isRecreating || activity.isChangingConfigurations) {
			startedActivities++
			return
		}
		startedActivities++
		if (startedActivities == 1 && lastBackgroundAt > 0L) {
			val elapsed = SystemClock.elapsedRealtime() - lastBackgroundAt
			if (elapsed >= settings.appProtectionTimeoutMillis) {
				isUnlocked = false
			}
		}
		updateActivityVisibility(activity)
		if (!isUnlocked && activity !is CrashReportDialog) {
			showProtectScreen(activity)
		}
	}

	override fun onActivityResumed(activity: Activity) {
		if (!ensureProtectionAvailability(activity)) {
			return
		}
		updateActivityVisibility(activity)
	}

	override fun onActivityStopped(activity: Activity) {
		if (!ensureProtectionAvailability(activity)) {
			return
		}
		if (activity is ProtectActivity || !settings.isAppProtectionEnabled) {
			return
		}
		startedActivities = (startedActivities - 1).coerceAtLeast(0)
		// Do not treat a programmatic recreation as going to background — the activity
		// is only stopping because it is being recreated in-place (theme/AMOLED change).
		if (recreationHandle.isRecreating || activity.isChangingConfigurations) {
			return
		}
		if (startedActivities == 0) {
			lastBackgroundAt = SystemClock.elapsedRealtime()
			if (settings.appProtectionTimeoutMillis == 0L) {
				isUnlocked = false
			}
		}
	}

	override fun onActivityDestroyed(activity: Activity) {
		if (activity is ProtectActivity) {
			// The protect screen was dismissed (unlock or cancel) — allow it to be shown again.
			isProtectScreenShowing = false
			return
		}
		if (activity.isFinishing && activity.isTaskRoot && settings.isAppProtectionEnabled) {
			restoreLock()
		}
	}

	fun unlock() {
		isUnlocked = true
		lastBackgroundAt = 0L
	}

	private fun restoreLock() {
		isUnlocked = !settings.isAppProtectionEnabled
	}

	private fun updateActivityVisibility(activity: Activity) {
		if (activity is ProtectActivity || activity is CrashReportDialog) {
			return
		}
		if (!settings.isAppProtectionEnabled) {
			activity.window.decorView.visibility = View.VISIBLE
			return
		}
		activity.window.decorView.visibility = if (isUnlocked) View.VISIBLE else View.INVISIBLE
	}

	private fun showProtectScreen(activity: Activity) {
		if (activity is ProtectActivity || activity.isFinishing || activity.isDestroyed) {
			return
		}
		// Prevent stacking multiple ProtectActivity instances on top of each other.
		if (isProtectScreenShowing) {
			return
		}
		isProtectScreenShowing = true
		val intent = Intent(activity, ProtectActivity::class.java).apply {
			addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
		}
		activity.startActivity(intent)
		@Suppress("DEPRECATION")
		activity.overridePendingTransition(0, 0)
	}

	private fun ensureProtectionAvailability(activity: Activity): Boolean {
		if (!settings.isAppProtectionEnabled) {
			return true
		}
		val canAuthenticate = BiometricManager.from(activity)
			.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS
		if (canAuthenticate) {
			return true
		}
		settings.isAppProtectionEnabled = false
		isUnlocked = true
		startedActivities = 0
		lastBackgroundAt = 0L
		return false
	}
}
