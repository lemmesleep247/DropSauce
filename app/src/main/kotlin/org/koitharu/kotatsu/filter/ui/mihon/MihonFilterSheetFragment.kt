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
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetFilterMihonBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator

@AndroidEntryPoint
class MihonFilterSheetFragment : BaseAdaptiveSheet<SheetFilterMihonBinding>(), AdaptiveSheetCallback {

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
		addSheetCallback(this, viewLifecycleOwner)
	}

	override fun onStart() {
		super.onStart()
		setHalfExpanded()
	}

	override fun onStateChanged(sheet: View, newState: Int) {
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		// Snap the drag handle to its resting state for programmatic moves; manual drags drive it via onSlide.
		viewBinding?.headerBar?.setDragHandleCollapseProgress(if (newState == STATE_EXPANDED) 1f else 0f)
	}

	override fun onSlide(sheet: View, slideOffset: Float) {
		// Melt the drag handle away over the top stretch of the drag so reaching full screen is one
		// seamless motion rather than the handle snapping out once expanded.
		val binding = viewBinding ?: return
		val progress = (slideOffset - DRAG_HANDLE_COLLAPSE_START) / (1f - DRAG_HANDLE_COLLAPSE_START)
		binding.headerBar.setDragHandleCollapseProgress(progress)
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
		viewBinding?.recyclerView?.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
		)
		// The action buttons now sit at the bottom, so the navigation-bar inset must keep them clear.
		// Preserve the layout's own vertical breathing room on top of the system inset.
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		viewBinding?.layoutBottom?.updatePadding(bottom = basePadding + barsInsets.bottom)
		return insets.consume(v, typeMask, bottom = true)
	}

	private companion object {
		// Slide offset (0 = half, 1 = full screen) at which the drag handle starts collapsing. Kept above
		// the half-expanded resting offset so the handle stays full at the centre position.
		const val DRAG_HANDLE_COLLAPSE_START = 0.65f
	}
}
