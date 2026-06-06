package org.koitharu.kotatsu.list.ui.adapter

import org.koitharu.kotatsu.list.domain.ListFilterOption

interface QuickFilterClickListener {

	fun onFilterOptionClick(option: ListFilterOption)

	fun onFilterOptionChanged(option: ListFilterOption, isApplied: Boolean) = Unit

	fun onFilterOptionsCleared(options: Collection<ListFilterOption>) = Unit
}
