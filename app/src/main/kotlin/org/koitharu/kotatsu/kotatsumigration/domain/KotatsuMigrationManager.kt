package org.koitharu.kotatsu.kotatsumigration.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the live state of a running Kotatsu→Mihon migration so the Backup & Restore settings row
 * can reflect progress (and disable re-entry while one is in flight).
 */
@Singleton
class KotatsuMigrationManager @Inject constructor() {

	private val _state = MutableStateFlow<MigrationState>(MigrationState.Idle)
	val state: StateFlow<MigrationState> = _state.asStateFlow()

	val isRunning: Boolean
		get() = _state.value is MigrationState.Running

	fun onStart(total: Int) {
		_state.value = MigrationState.Running(done = 0, total = total, migrated = 0)
	}

	fun onProgress(done: Int, total: Int, migrated: Int) {
		_state.value = MigrationState.Running(done = done, total = total, migrated = migrated)
	}

	fun onFinish(summary: MigrationSummary) {
		_state.value = MigrationState.Finished(summary)
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
 * @param missingExtensions display names of extensions that need installing (had matches but the
 *        extension wasn't installed), de-duplicated.
 */
data class MigrationSummary(
	val total: Int,
	val migrated: Int,
	val needsExtension: Int,
	val noMapping: Int,
	val failed: Int,
	val missingExtensions: Set<String>,
) {
	val skipped: Int get() = needsExtension + noMapping + failed
}
