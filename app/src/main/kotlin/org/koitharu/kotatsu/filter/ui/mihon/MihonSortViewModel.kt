package org.koitharu.kotatsu.filter.ui.mihon

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.titleRes
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.FilterCoordinator.SortState
import org.koitharu.kotatsu.filter.ui.mihon.model.SortOptionModel

@HiltViewModel(assistedFactory = MihonSortViewModel.Factory::class)
class MihonSortViewModel @AssistedInject constructor(
	@Assisted private val filter: FilterCoordinator,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private var sortState: SortState? = null
	private val contentFlow = MutableStateFlow<List<SortOptionModel>>(emptyList())

	val content: StateFlow<List<SortOptionModel>> = contentFlow

	init {
		launchLoadingJob(Dispatchers.Default) {
			sortState = filter.loadSortState()
			rebuild()
		}
	}

	fun onOptionClick(model: SortOptionModel) {
		when (val state = sortState) {
			is SortState.Source -> {
				val index = model.id
				if (state.supportsDirection) {
					// Tapping the selected option flips direction; a new option keeps ascending.
					val ascending = if (index == state.selectedIndex) !state.isAscending else true
					filter.applySourceSort(index, ascending)
					sortState = state.copy(selectedIndex = index, isAscending = ascending)
				} else {
					filter.applySourceSort(index, false)
					sortState = state.copy(selectedIndex = index)
				}
				rebuild()
			}

			is SortState.Native -> {
				val order = state.options.firstOrNull { it.ordinal == model.id } ?: return
				filter.setSortOrder(order)
				sortState = state.copy(selected = order)
				rebuild()
			}

			null -> Unit
		}
	}

	private fun rebuild() {
		contentFlow.value = when (val state = sortState) {
			is SortState.Source -> state.options.mapIndexed { index, label ->
				SortOptionModel(
					id = index,
					title = label,
					indicator = when {
						index != state.selectedIndex -> SortOptionModel.Indicator.NONE
						!state.supportsDirection -> SortOptionModel.Indicator.SELECTED
						state.isAscending -> SortOptionModel.Indicator.ASCENDING
						else -> SortOptionModel.Indicator.DESCENDING
					},
				)
			}

			is SortState.Native -> state.options.map { order ->
				SortOptionModel(
					id = order.ordinal,
					title = context.getString(order.titleRes),
					indicator = if (order == state.selected) {
						SortOptionModel.Indicator.SELECTED
					} else {
						SortOptionModel.Indicator.NONE
					},
				)
			}

			null -> emptyList()
		}
	}

	@AssistedFactory
	interface Factory {
		fun create(filter: FilterCoordinator): MihonSortViewModel
	}
}
