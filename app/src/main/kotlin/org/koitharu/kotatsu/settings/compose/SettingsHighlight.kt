package org.koitharu.kotatsu.settings.compose

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tiny process-wide bus that lets the settings-search flow ask a freshly-opened Compose screen
 * to highlight a specific row once. Rows are matched by their (localized) title — every
 * [SettingsItem] already has one, so no per-row wiring is needed.
 *
 * [SettingsActivity] sets [pendingTitle] right after navigating to a search result; the matching
 * [SettingsItem] flashes its background a single time and then calls [consume] to clear it, so the
 * highlight never repeats on recomposition or re-entry.
 */
object SettingsSearchHighlight {

	val pendingTitle = MutableStateFlow<String?>(null)

	fun request(title: String?) {
		pendingTitle.value = title?.takeIf { it.isNotBlank() }
	}

	fun consume(title: String) {
		if (pendingTitle.value == title) {
			pendingTitle.value = null
		}
	}
}
