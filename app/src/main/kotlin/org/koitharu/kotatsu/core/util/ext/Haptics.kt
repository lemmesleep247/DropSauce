package org.koitharu.kotatsu.core.util.ext

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.preference.PreferenceManager
import org.koitharu.kotatsu.core.prefs.AppSettings

/**
 * Semantic haptic effects used throughout the app.
 *
 * Each value maps to the most expressive [HapticFeedbackConstants] available on the running
 * platform, falling back gracefully on older API levels (the app supports minSdk 23). The
 * intent follows Material 3 Expressive / Jetpack haptics guidance: pick feedback that matches
 * the *meaning* of an interaction instead of firing one generic tap everywhere.
 */
enum class HapticEffect {

	/** A light confirmation for an ordinary tap (button, nav item, list-row open). */
	CLICK,

	/** A very light confirmation for dense tool buttons. */
	LIGHT_CLICK,

	/** A discrete tick for stepping through values — slider stops, segmented progress. */
	TICK,

	/** A softer frequent tick for dense sliders and scrubbers. */
	LIGHT_TICK,

	/** Turning a binary control on. */
	TOGGLE_ON,

	/** Turning a binary control off. */
	TOGGLE_OFF,

	/** A committed, successful action (selection confirmed, page / chapter switched). */
	CONFIRM,

	/** A blocked or invalid action, e.g. trying to page past the last page. */
	REJECT,

	/** A long-press being recognised (entering selection mode, opening a context menu). */
	LONG_PRESS,

	/** A continuous gesture begins — drag / slider touch-down, pull-to-refresh arming. */
	GESTURE_START,

	/** A continuous gesture commits or ends — slider release, pull-to-refresh firing. */
	GESTURE_END,
}

private fun HapticEffect.toConstant(): Int = when (this) {
	HapticEffect.CLICK -> HapticFeedbackConstants.VIRTUAL_KEY

	HapticEffect.LIGHT_CLICK -> HapticFeedbackConstants.KEYBOARD_TAP

	HapticEffect.TICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		HapticFeedbackConstants.SEGMENT_TICK
	} else {
		HapticFeedbackConstants.CLOCK_TICK
	}

	HapticEffect.LIGHT_TICK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
	} else {
		HapticFeedbackConstants.KEYBOARD_TAP
	}

	HapticEffect.TOGGLE_ON -> when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.TOGGLE_ON
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
		else -> HapticFeedbackConstants.VIRTUAL_KEY
	}

	HapticEffect.TOGGLE_OFF -> when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.TOGGLE_OFF
		else -> HapticFeedbackConstants.CLOCK_TICK
	}

	HapticEffect.CONFIRM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		HapticFeedbackConstants.CONFIRM
	} else {
		HapticFeedbackConstants.KEYBOARD_TAP
	}

	HapticEffect.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		HapticFeedbackConstants.REJECT
	} else {
		HapticFeedbackConstants.LONG_PRESS
	}

	HapticEffect.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS

	HapticEffect.GESTURE_START -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		HapticFeedbackConstants.GESTURE_START
	} else {
		HapticFeedbackConstants.CLOCK_TICK
	}

	HapticEffect.GESTURE_END -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		HapticFeedbackConstants.GESTURE_END
	} else {
		HapticFeedbackConstants.CLOCK_TICK
	}
}

private fun isHapticFeedbackEnabledInApp(context: Context): Boolean =
	PreferenceManager.getDefaultSharedPreferences(context)
		.getBoolean(AppSettings.KEY_HAPTIC_FEEDBACK, true)

/**
 * Perform a semantic [HapticEffect] on this view, honouring the app-wide haptic toggle
 * (Settings → Appearance). The view's own `isHapticFeedbackEnabled` flag is bypassed so the
 * feedback fires regardless of the widget type, but the system-wide haptic setting is still
 * respected — disabling touch feedback in Android settings silences these too.
 */
fun View.hapticFeedback(effect: HapticEffect) {
	if (!isHapticFeedbackEnabledInApp(context)) {
		return
	}
	performHapticFeedback(effect.toConstant(), HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
}

/**
 * Returns a callback that performs semantic [HapticEffect]s from Compose, routed through the
 * host [android.view.View] so it shares the exact same platform constants, fallbacks and
 * app-wide toggle as the rest of the app.
 */
@Composable
fun rememberHapticEffect(): (HapticEffect) -> Unit {
	val view = LocalView.current
	return remember(view) { { effect: HapticEffect -> view.hapticFeedback(effect) } }
}
