package org.koitharu.kotatsu.filter.ui.mihon

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.databinding.ItemFilterCheckboxBinding
import org.koitharu.kotatsu.databinding.ItemFilterChipsBinding
import org.koitharu.kotatsu.databinding.ItemFilterExpandableBinding
import org.koitharu.kotatsu.databinding.ItemFilterHeaderBinding
import org.koitharu.kotatsu.databinding.ItemFilterSeparatorBinding
import org.koitharu.kotatsu.databinding.ItemFilterSelectBinding
import org.koitharu.kotatsu.databinding.ItemFilterSortOptionBinding
import org.koitharu.kotatsu.databinding.ItemFilterTextBinding
import org.koitharu.kotatsu.databinding.ItemFilterTristateBinding
import org.koitharu.kotatsu.filter.ui.mihon.model.MihonFilterItem
import org.koitharu.kotatsu.list.ui.adapter.ListItemType

/** Callbacks from the dynamic Mihon filter sheet rows. */
interface MihonFilterListener {
	fun onCheckBoxClick(item: MihonFilterItem.CheckBox)
	fun onCheckBoxChipClick(checkBoxPath: String)
	fun onTriStateClick(item: MihonFilterItem.TriState)
	fun onTextChanged(item: MihonFilterItem.Text, value: String)
	fun onSelectChanged(item: MihonFilterItem.Select, index: Int)
	fun onExpandClick(item: MihonFilterItem.ExpandableHeader)
	fun onSortOptionClick(item: MihonFilterItem.SortOption)
}

class MihonFilterAdapter(
	listener: MihonFilterListener,
) : BaseListAdapter<MihonFilterItem>() {

	init {
		addDelegate(ListItemType.MIHON_FILTER_HEADER, headerDelegate())
		addDelegate(ListItemType.MIHON_FILTER_SEPARATOR, separatorDelegate())
		addDelegate(ListItemType.MIHON_FILTER_CHECKBOX, checkBoxDelegate(listener))
		addDelegate(ListItemType.MIHON_FILTER_CHIPS, checkBoxChipsDelegate(listener))
		addDelegate(ListItemType.MIHON_FILTER_TRISTATE, triStateDelegate(listener))
		addDelegate(ListItemType.MIHON_FILTER_TEXT, textDelegate(listener))
		addDelegate(ListItemType.MIHON_FILTER_SELECT, selectDelegate(listener))
		addDelegate(ListItemType.MIHON_FILTER_EXPANDABLE, expandableDelegate(listener))
		addDelegate(ListItemType.MIHON_FILTER_SORT_OPTION, sortOptionDelegate(listener))
	}
}

private const val INDENT_DP = 16

private fun View.applyPaddingIndent(depth: Int) {
	val step = (INDENT_DP * resources.displayMetrics.density).toInt()
	val base = resources.getDimensionPixelOffset(R.dimen.margin_normal)
	updatePaddingRelative(start = base + depth * step)
}

private fun View.applyMarginIndent(depth: Int) {
	val step = (INDENT_DP * resources.displayMetrics.density).toInt()
	val base = resources.getDimensionPixelOffset(R.dimen.margin_normal)
	updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
		marginStart = base + depth * step
	}
}

private fun headerDelegate() =
	adapterDelegateViewBinding<MihonFilterItem.Header, MihonFilterItem, ItemFilterHeaderBinding>(
		{ inflater, parent -> ItemFilterHeaderBinding.inflate(inflater, parent, false) },
	) {
		bind {
			binding.textViewTitle.text = item.title
			binding.root.applyPaddingIndent(item.depth)
		}
	}

private fun separatorDelegate() =
	adapterDelegateViewBinding<MihonFilterItem.Separator, MihonFilterItem, ItemFilterSeparatorBinding>(
		{ inflater, parent -> ItemFilterSeparatorBinding.inflate(inflater, parent, false) },
	) {
		// Nothing to bind.
	}

private fun checkBoxDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.CheckBox, MihonFilterItem, ItemFilterCheckboxBinding>(
		{ inflater, parent -> ItemFilterCheckboxBinding.inflate(inflater, parent, false) },
	) {
		binding.layoutRoot.setOnClickListener { listener.onCheckBoxClick(item) }
		bind {
			binding.textViewTitle.text = item.title
			binding.checkbox.isChecked = item.isChecked
			binding.layoutRoot.applyPaddingIndent(item.depth)
		}
	}

private fun checkBoxChipsDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.CheckBoxChips, MihonFilterItem, ItemFilterChipsBinding>(
		{ inflater, parent -> ItemFilterChipsBinding.inflate(inflater, parent, false) },
	) {
		binding.chipsView.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
			(data as? String)?.let(listener::onCheckBoxChipClick)
		}
		bind {
			binding.chipsView.setChips(
				item.chips.map { chip ->
					ChipsView.ChipModel(
						title = chip.title,
						isChecked = chip.checked,
						data = chip.path,
					)
				},
			)
			binding.chipsView.applyMarginIndent(item.depth)
		}
	}

private fun triStateDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.TriState, MihonFilterItem, ItemFilterTristateBinding>(
		{ inflater, parent -> ItemFilterTristateBinding.inflate(inflater, parent, false) },
	) {
		binding.layoutRoot.setOnClickListener { listener.onTriStateClick(item) }
		bind {
			binding.textViewTitle.text = item.title
			val iconRes = when (item.state) {
				eu.kanade.tachiyomi.source.model.Filter.TriState.STATE_INCLUDE -> R.drawable.ic_filter_tri_include
				eu.kanade.tachiyomi.source.model.Filter.TriState.STATE_EXCLUDE -> R.drawable.ic_filter_tri_exclude
				else -> R.drawable.ic_filter_tri_ignore
			}
			binding.imageViewState.setImageResource(iconRes)
			binding.layoutRoot.applyPaddingIndent(item.depth)
		}
	}

private fun textDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.Text, MihonFilterItem, ItemFilterTextBinding>(
		{ inflater, parent -> ItemFilterTextBinding.inflate(inflater, parent, false) },
	) {
		fun commit() {
			listener.onTextChanged(item, binding.editText.text?.toString().orEmpty())
		}
		binding.editText.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE) {
				commit()
				binding.editText.clearFocus()
				true
			} else {
				false
			}
		}
		binding.editText.setOnFocusChangeListener { _, hasFocus ->
			if (!hasFocus) {
				commit()
			}
		}
		bind {
			binding.layoutInput.hint = item.title
			if (binding.editText.text?.toString() != item.value) {
				binding.editText.setText(item.value)
			}
			binding.layoutInput.applyMarginIndent(item.depth)
		}
	}

private fun selectDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.Select, MihonFilterItem, ItemFilterSelectBinding>(
		{ inflater, parent -> ItemFilterSelectBinding.inflate(inflater, parent, false) },
	) {
		binding.editSelect.setOnItemClickListener { _, _, position, _ ->
			listener.onSelectChanged(item, position)
		}
		bind {
			binding.layoutInput.hint = item.title
			binding.editSelect.setAdapter(
				ArrayAdapter(context, android.R.layout.simple_list_item_1, item.options),
			)
			val selected = item.options.getOrNull(item.selectedIndex).orEmpty()
			binding.editSelect.setText(selected, false)
			binding.layoutInput.applyMarginIndent(item.depth)
		}
	}

private fun expandableDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.ExpandableHeader, MihonFilterItem, ItemFilterExpandableBinding>(
		{ inflater, parent -> ItemFilterExpandableBinding.inflate(inflater, parent, false) },
	) {
		binding.layoutRoot.setOnClickListener { listener.onExpandClick(item) }
		bind {
			binding.textViewTitle.text = item.title
			val summary = item.activeSummary
			if (summary.isNullOrEmpty()) {
				binding.textViewSummary.visibility = View.GONE
			} else {
				binding.textViewSummary.visibility = View.VISIBLE
				binding.textViewSummary.text = summary
			}
			binding.imageViewChevron.rotation = if (item.isExpanded) 0f else 180f
			binding.layoutRoot.applyPaddingIndent(item.depth)
		}
	}

private fun sortOptionDelegate(listener: MihonFilterListener) =
	adapterDelegateViewBinding<MihonFilterItem.SortOption, MihonFilterItem, ItemFilterSortOptionBinding>(
		{ inflater, parent -> ItemFilterSortOptionBinding.inflate(inflater, parent, false) },
	) {
		binding.layoutRoot.setOnClickListener { listener.onSortOptionClick(item) }
		bind {
			binding.textViewTitle.text = item.title
			when (item.isAscending) {
				true -> {
					binding.imageViewArrow.visibility = View.VISIBLE
					binding.imageViewArrow.rotation = 0f
				}

				false -> {
					binding.imageViewArrow.visibility = View.VISIBLE
					binding.imageViewArrow.rotation = 180f
				}

				null -> binding.imageViewArrow.visibility = View.INVISIBLE
			}
			binding.layoutRoot.applyPaddingIndent(item.depth)
		}
	}
