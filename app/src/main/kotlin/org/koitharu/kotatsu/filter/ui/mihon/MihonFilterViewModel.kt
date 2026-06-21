package org.koitharu.kotatsu.filter.ui.mihon

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.mihon.model.MihonFilterItem

@HiltViewModel(assistedFactory = MihonFilterViewModel.Factory::class)
class MihonFilterViewModel @AssistedInject constructor(
	@Assisted private val filter: FilterCoordinator,
) : BaseViewModel(), MihonFilterListener {

	private var working: FilterList = FilterList()
	private var defaults: FilterList = FilterList()
	private val pathMap = HashMap<String, Filter<*>>()
	private val expandedPaths = HashSet<String>()

	private val itemsFlow = MutableStateFlow<List<MihonFilterItem>>(emptyList())
	private val loadedFlow = MutableStateFlow(false)

	val items: StateFlow<List<MihonFilterItem>> = itemsFlow

	val isEmptyState: StateFlow<Boolean> = combine(loadedFlow, itemsFlow) { loaded, list ->
		loaded && list.isEmpty()
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	init {
		load(resetExpanded = true)
	}

	private fun load(resetExpanded: Boolean) = launchLoadingJob(Dispatchers.Default) {
		val wf = filter.loadWorkingFilters()
		working = wf.working
		defaults = wf.defaults
		if (resetExpanded) {
			expandedPaths.clear()
			collectInitiallyExpanded(working.toList(), prefix = "")
		}
		rebuild()
		loadedFlow.value = true
	}

	fun reset() {
		filter.reset()
		load(resetExpanded = true)
	}

	override fun onCheckBoxClick(item: MihonFilterItem.CheckBox) {
		val f = pathMap[item.path] as? Filter.CheckBox ?: return
		f.state = !f.state
		applyAndRebuild()
	}

	override fun onCheckBoxChipClick(checkBoxPath: String) {
		val f = pathMap[checkBoxPath] as? Filter.CheckBox ?: return
		f.state = !f.state
		applyAndRebuild()
	}

	override fun onTriStateClick(item: MihonFilterItem.TriState) {
		val f = pathMap[item.path] as? Filter.TriState ?: return
		f.state = when (f.state) {
			Filter.TriState.STATE_IGNORE -> Filter.TriState.STATE_INCLUDE
			Filter.TriState.STATE_INCLUDE -> Filter.TriState.STATE_EXCLUDE
			else -> Filter.TriState.STATE_IGNORE
		}
		applyAndRebuild()
	}

	override fun onTextChanged(item: MihonFilterItem.Text, value: String) {
		val f = pathMap[item.path] as? Filter.Text ?: return
		if (f.state == value) {
			return
		}
		f.state = value
		applyAndRebuild()
	}

	override fun onSelectChanged(item: MihonFilterItem.Select, index: Int) {
		val f = pathMap[item.path] as? Filter.Select<*> ?: return
		if (index !in f.values.indices || f.state == index) {
			return
		}
		f.state = index
		applyAndRebuild()
	}

	override fun onExpandClick(item: MihonFilterItem.ExpandableHeader) {
		if (!expandedPaths.add(item.path)) {
			expandedPaths.remove(item.path)
		}
		rebuild()
	}

	override fun onSortOptionClick(item: MihonFilterItem.SortOption) {
		val f = pathMap[item.path] as? Filter.Sort ?: return
		val current = f.state
		val ascending = if (current?.index == item.optionIndex) {
			!current.ascending
		} else {
			current?.ascending ?: true
		}
		f.state = Filter.Sort.Selection(item.optionIndex, ascending)
		applyAndRebuild()
	}

	private fun applyAndRebuild() {
		filter.applyDynamicFilters(working, defaults)
		rebuild()
	}

	private fun rebuild() {
		pathMap.clear()
		val result = ArrayList<MihonFilterItem>()
		build(working.toList(), prefix = "", depth = 0, out = result)
		itemsFlow.value = result
	}

	private fun build(filters: List<Filter<*>>, prefix: String, depth: Int, out: MutableList<MihonFilterItem>) {
		filters.forEachIndexed { index, f ->
			val path = if (prefix.isEmpty()) index.toString() else "$prefix.$index"
			pathMap[path] = f
			when (f) {
				is Filter.Header -> if (f.name.isNotEmpty()) {
					out += MihonFilterItem.Header(path, depth, f.name)
				}

				is Filter.Separator -> out += MihonFilterItem.Separator(path, depth)

				is Filter.CheckBox -> out += MihonFilterItem.CheckBox(path, depth, f.name, f.state)

				is Filter.TriState -> out += MihonFilterItem.TriState(path, depth, f.name, f.state)

				is Filter.Text -> out += MihonFilterItem.Text(path, depth, f.name, f.state)

				is Filter.Select<*> -> out += MihonFilterItem.Select(
					path = path,
					depth = depth,
					title = f.name,
					options = f.values.map { it.toString() },
					selectedIndex = f.state,
				)

				is Filter.Sort -> {
					val selection = f.state
					val summary = selection?.let {
						"${f.values.getOrNull(it.index)} ${if (it.ascending) "↑" else "↓"}"
					}
					val expanded = path in expandedPaths
					out += MihonFilterItem.ExpandableHeader(path, depth, f.name, expanded, summary)
					if (expanded) {
						f.values.forEachIndexed { optionIndex, label ->
							val isAscending = if (selection?.index == optionIndex) selection.ascending else null
							out += MihonFilterItem.SortOption(path, depth + 1, optionIndex, label, isAscending)
						}
					}
				}

				is Filter.Group<*> -> {
					val children = f.state.filterIsInstance<Filter<*>>()
					val active = activeCount(children)
					val expanded = path in expandedPaths
					out += MihonFilterItem.ExpandableHeader(
						path = path,
						depth = depth,
						title = f.name,
						isExpanded = expanded,
						activeSummary = if (active > 0) active.toString() else null,
					)
					if (expanded) {
						// A small group of plain on/off checkboxes renders as a wrapping chip row instead of
						// one row per option. Large taxonomies (e.g. thousands of publishers) stay a scrollable
						// list — ChipGroup doesn't recycle, so that many chips would freeze the UI.
						if (children.isNotEmpty() &&
							children.size <= MAX_CHIP_GROUP_SIZE &&
							children.all { it is Filter.CheckBox }
						) {
							val chips = children.mapIndexed { childIndex, child ->
								val childPath = "$path.$childIndex"
								pathMap[childPath] = child
								val checkBox = child as Filter.CheckBox
								MihonFilterItem.CheckBoxChips.Chip(childPath, checkBox.name, checkBox.state)
							}
							out += MihonFilterItem.CheckBoxChips(path, depth + 1, chips)
						} else {
							build(children, path, depth + 1, out)
						}
					}
				}
			}
		}
	}

	private fun collectInitiallyExpanded(filters: List<Filter<*>>, prefix: String) {
		filters.forEachIndexed { index, f ->
			val path = if (prefix.isEmpty()) index.toString() else "$prefix.$index"
			when (f) {
				is Filter.Sort -> if (f.state != null) expandedPaths.add(path)
				is Filter.Group<*> -> {
					val children = f.state.filterIsInstance<Filter<*>>()
					if (activeCount(children) > 0) {
						expandedPaths.add(path)
					}
					collectInitiallyExpanded(children, path)
				}

				else -> Unit
			}
		}
	}

	private fun activeCount(filters: List<Filter<*>>): Int = filters.count { f ->
		when (f) {
			is Filter.CheckBox -> f.state
			is Filter.TriState -> f.state != Filter.TriState.STATE_IGNORE
			is Filter.Text -> f.state.isNotEmpty()
			is Filter.Select<*> -> f.state != 0
			else -> false
		}
	}

	@AssistedFactory
	interface Factory {
		fun create(filter: FilterCoordinator): MihonFilterViewModel
	}

	private companion object {
		// Above this many checkbox options a group stays a (recycling) list rather than a chip row.
		const val MAX_CHIP_GROUP_SIZE = 64
	}
}
