package org.koitharu.kotatsu.settings.developer

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.core.ui.BaseViewModel
import javax.inject.Inject

data class DeveloperToolsUiState(
	val isRunning: Boolean = false,
	val completed: Int = 0,
	val total: Int = 0,
	val results: List<DeveloperExtensionTestResult> = emptyList(),
	val errorMessage: String? = null,
	val hasRun: Boolean = false,
)

@HiltViewModel
class DeveloperToolsViewModel @Inject constructor(
	private val runner: DeveloperExtensionTestRunner,
) : BaseViewModel() {

	private val _uiState = MutableStateFlow(DeveloperToolsUiState())
	val uiState: StateFlow<DeveloperToolsUiState> = _uiState.asStateFlow()

	private var runJob: Job? = null

	fun runAll() {
		if (runJob?.isActive == true) return
		_uiState.update {
			it.copy(isRunning = true, completed = 0, total = 0, results = emptyList(), errorMessage = null)
		}
		runJob = launchJob(SkipErrors) {
			try {
				val results = runner.run { completed, total, result ->
					_uiState.update { state ->
						state.copy(
							completed = completed,
							total = total,
							results = if (result == null) {
								state.results
							} else {
								(state.results + result).sortedBy { it.extensionName.lowercase() }
							},
						)
					}
				}
				_uiState.update {
					it.copy(
						isRunning = false,
						completed = results.size,
						total = results.size,
						results = results.sortedBy { result -> result.extensionName.lowercase() },
						hasRun = true,
					)
				}
			} catch (e: CancellationException) {
				_uiState.update { it.copy(isRunning = false) }
				throw e
			} catch (e: Throwable) {
				_uiState.update {
					it.copy(
						isRunning = false,
						errorMessage = e.message ?: e.javaClass.simpleName,
						hasRun = true,
					)
				}
			}
		}
	}

	fun cancel() {
		runJob?.cancel()
		runJob = null
		_uiState.update { it.copy(isRunning = false) }
	}
}
