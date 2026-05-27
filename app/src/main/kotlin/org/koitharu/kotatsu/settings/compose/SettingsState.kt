package org.koitharu.kotatsu.settings.compose

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Compose state bound to a SharedPreferences key — the same prefs file `AppSettings` reads
 * from (`PreferenceManager.getDefaultSharedPreferences`). Re-renders on external changes
 * (e.g. legacy preference fragments writing the same key).
 *
 * Use these inside a settings screen so toggles/dialogs feel instantly responsive while
 * staying source-of-truth-consistent with the rest of the app.
 */

@Composable
fun rememberBooleanPref(key: String, defaultValue: Boolean): MutableState<Boolean> =
	rememberPrefValue(
		key = key,
		read = { prefs -> prefs.getBoolean(key, defaultValue) },
		write = { prefs, value -> prefs.edit { putBoolean(key, value) } },
	)

@Composable
fun rememberIntPref(key: String, defaultValue: Int): MutableState<Int> = rememberPrefValue(
	key = key,
	read = { prefs -> prefs.getInt(key, defaultValue) },
	write = { prefs, value -> prefs.edit { putInt(key, value) } },
)

@Composable
fun rememberStringPref(key: String, defaultValue: String): MutableState<String> =
	rememberPrefValue(
		key = key,
		read = { prefs -> prefs.getString(key, defaultValue) ?: defaultValue },
		write = { prefs, value -> prefs.edit { putString(key, value) } },
	)

@Composable
fun rememberStringSetPref(
	key: String,
	defaultValue: Set<String>,
): MutableState<Set<String>> = rememberPrefValue(
	key = key,
	read = { prefs -> prefs.getStringSet(key, defaultValue) ?: defaultValue },
	write = { prefs, value -> prefs.edit { putStringSet(key, value) } },
)

/**
 * Generic underlying primitive used by the typed helpers above. Hooks an
 * OnSharedPreferenceChangeListener that updates the Compose state whenever the key
 * changes externally.
 */
@Composable
private fun <T> rememberPrefValue(
	key: String,
	read: (SharedPreferences) -> T,
	write: (SharedPreferences, T) -> Unit,
): MutableState<T> {
	val context = LocalContext.current
	val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
	val state = remember(prefs, key) { mutableStateOf(read(prefs)) }

	DisposableEffect(prefs, key) {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
			if (changedKey == key) {
				state.value = read(sp)
			}
		}
		prefs.registerOnSharedPreferenceChangeListener(listener)
		onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}

	return remember(state, prefs) {
		object : MutableState<T> {
			override var value: T
				get() = state.value
				set(newValue) {
					if (state.value != newValue) {
						state.value = newValue
						write(prefs, newValue)
					}
				}

			override fun component1(): T = value
			override fun component2(): (T) -> Unit = { value = it }
		}
	}
}

/** Convenience accessor for screens that need the prefs handle directly (e.g. for compound writes). */
@Composable
fun rememberDefaultSharedPreferences(): SharedPreferences {
	val context = LocalContext.current
	return remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
}
