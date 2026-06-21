package org.koitharu.kotatsu.filter.ui.mihon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ItemSortOptionBinding
import org.koitharu.kotatsu.databinding.SheetSortBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.mihon.model.SortOptionModel
import org.koitharu.kotatsu.list.ui.adapter.ListItemType

@AndroidEntryPoint
class MihonSortSheet : BaseAdaptiveSheet<SheetSortBinding>() {

	private val viewModel by viewModels<MihonSortViewModel>(
		extrasProducer = {
			defaultViewModelCreationExtras.withCreationCallback<MihonSortViewModel.Factory> { factory ->
				factory.create(FilterCoordinator.require(this))
			}
		},
	)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetSortBinding {
		return SheetSortBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetSortBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<SortOptionModel>()
			.addDelegate(ListItemType.MIHON_SORT_OPTION, sortOptionDelegate(viewModel::onOptionClick))
		binding.recyclerView.layoutManager = LinearLayoutManager(binding.root.context)
		binding.recyclerView.adapter = adapter
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding?.recyclerView?.setPadding(barsInsets.left, 0, barsInsets.right, barsInsets.bottom)
		return insets.consumeAll(typeMask)
	}
}

private fun sortOptionDelegate(
	onClick: (SortOptionModel) -> Unit,
) = adapterDelegateViewBinding<SortOptionModel, SortOptionModel, ItemSortOptionBinding>(
	{ inflater, parent -> ItemSortOptionBinding.inflate(inflater, parent, false) },
) {
	binding.layoutRoot.setOnClickListener { onClick(item) }
	bind {
		binding.textViewTitle.text = item.title
		when (item.indicator) {
			SortOptionModel.Indicator.NONE -> binding.imageViewArrow.isInvisible = true
			SortOptionModel.Indicator.ASCENDING -> {
				binding.imageViewArrow.isInvisible = false
				binding.imageViewArrow.setImageResource(R.drawable.ic_arrow_up)
				binding.imageViewArrow.rotation = 0f
			}

			SortOptionModel.Indicator.DESCENDING -> {
				binding.imageViewArrow.isInvisible = false
				binding.imageViewArrow.setImageResource(R.drawable.ic_arrow_up)
				binding.imageViewArrow.rotation = 180f
			}

			SortOptionModel.Indicator.SELECTED -> {
				binding.imageViewArrow.isInvisible = false
				binding.imageViewArrow.setImageResource(R.drawable.ic_check)
				binding.imageViewArrow.rotation = 0f
			}
		}
	}
}
