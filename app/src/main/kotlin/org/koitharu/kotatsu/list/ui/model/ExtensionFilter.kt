package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.list.domain.ListFilterOption

data class ExtensionFilter(
	val options: List<ListFilterOption.Source>,
	val selectedOptions: Set<ListFilterOption.Source>,
) {

	val isActive: Boolean
		get() = selectedOptions.isNotEmpty()
}
