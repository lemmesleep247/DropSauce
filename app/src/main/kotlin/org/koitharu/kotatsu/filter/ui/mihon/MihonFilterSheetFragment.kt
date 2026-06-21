package org.koitharu.kotatsu.filter.ui.mihon

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetFilterMihonBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator

@AndroidEntryPoint
class MihonFilterSheetFragment : BaseAdaptiveSheet<SheetFilterMihonBinding>() {

	private val viewModel by viewModels<MihonFilterViewModel>(
		extrasProducer = {
			defaultViewModelCreationExtras.withCreationCallback<MihonFilterViewModel.Factory> { factory ->
				factory.create(FilterCoordinator.require(this))
			}
		},
	)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetFilterMihonBinding {
		return SheetFilterMihonBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetFilterMihonBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		if (dialog == null) {
			binding.adjustForEmbeddedLayout()
		}
		val adapter = MihonFilterAdapter(viewModel)
		binding.recyclerView.layoutManager = LinearLayoutManager(binding.root.context)
		binding.recyclerView.adapter = adapter
		binding.buttonReset.setOnClickListener { viewModel.reset() }
		binding.buttonDone.setOnClickListener { dismiss() }
		viewModel.items.observe(viewLifecycleOwner, adapter)
		viewModel.isLoading.observe(viewLifecycleOwner) {
			binding.progressBar.isVisible = it
		}
		viewModel.isEmptyState.observe(viewLifecycleOwner) {
			binding.textViewHolder.isVisible = it
		}
		// Anchor the sheet to full height. A weighted RecyclerView inside a wrap_content sheet otherwise
		// collapses and the sheet floats with a gap beneath it (no-op when embedded as a side panel).
		disableFitToContents()
	}

	private fun SheetFilterMihonBinding.adjustForEmbeddedLayout() {
		buttonDone.isVisible = false
		root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
		buttonReset.updateLayoutParams<LinearLayout.LayoutParams> {
			weight = 0f
			width = LinearLayout.LayoutParams.WRAP_CONTENT
			gravity = Gravity.END or Gravity.CENTER_VERTICAL
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding?.layoutBottom?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			bottomMargin = barsInsets.bottom
		}
		viewBinding?.recyclerView?.updatePadding(left = barsInsets.left, right = barsInsets.right)
		return insets.consume(v, typeMask, bottom = true)
	}
}
