package org.koitharu.kotatsu.kotatsumigration.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the live state of a running Kotatsu→Mihon migration so the Backup & Restore settings row
 * can reflect progress (and disable re-entry while one is in flight). Also emits one-shot
 * [onStarted]/[onCompleted] events so whichever screen is in the foreground (settings, onboarding)
 * can show a "migration started" toast and a completion dialog.
 */
@Singleton
class KotatsuMigrationManager @Inject constructor() {

	private val _state = MutableStateFlow<MigrationState>(MigrationState.Idle)
	val state: StateFlow<MigrationState> = _state.asStateFlow()

	/** Fired when a run actually has entries to migrate (total > 0). Payload = total. */
	val onStarted = MutableEventFlow<Int>()

	/** Fired when a run that did work finishes. Payload = summary. */
	val onCompleted = MutableEventFlow<MigrationSummary>()

	val isRunning: Boolean
		get() = _state.value is MigrationState.Running

	fun onStart(total: Int) {
		_state.value = MigrationState.Running(done = 0, total = total, migrated = 0)
		if (total > 0) {
			onStarted.call(total)
		}
	}

	fun onProgress(done: Int, total: Int, migrated: Int) {
		_state.value = MigrationState.Running(done = done, total = total, migrated = migrated)
	}

	fun onFinish(summary: MigrationSummary) {
		_state.value = MigrationState.Finished(summary)
		if (summary.total > 0) {
			onCompleted.call(summary)
		}
	}

	fun reset() {
		_state.value = MigrationState.Idle
	}
}

sealed interface MigrationState {
	data object Idle : MigrationState
	data class Running(val done: Int, val total: Int, val migrated: Int) : MigrationState
	data class Finished(val summary: MigrationSummary) : MigrationState
}

/**
 * Outcome tally of a completed run.
 *
 * @param migrated entries converted whose extension is already installed (usable now).
 * @param pendingExtension entries converted but whose extension isn't installed yet.
 * @param missingExtensions display names of the extensions to install, de-duplicated.
 */
data class MigrationSummary(
	val total: Int,
	val migrated: Int,
	val pendingExtension: Int,
	val missingExtensions: Set<String>,
) {
	/** All entries that were re-keyed onto a Mihon source (ready now + pending install). */
	val converted: Int get() = migrated + pendingExtension
}
