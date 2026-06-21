package org.koitharu.kotatsu.filter.ui.mihon.model

import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_CHECKED_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel

/** A single row of the compact sort picker. [id] is the source sort index or the [java.lang.Enum.ordinal] of a SortOrder. */
class SortOptionModel(
	val id: Int,
	val title: String,
	val indicator: Indicator,
) : ListModel {

	enum class Indicator { NONE, ASCENDING, DESCENDING, SELECTED }

	override fun areItemsTheSame(other: ListModel) = other is SortOptionModel && other.id == id

	override fun equals(other: Any?) =
		other is SortOptionModel && other.id == id && other.title == title && other.indicator == indicator

	override fun hashCode() = id * 31 + indicator.ordinal

	override fun getChangePayload(previousState: ListModel) =
		if (previousState is SortOptionModel && previousState.indicator != indicator) PAYLOAD_CHECKED_CHANGED else null
}
