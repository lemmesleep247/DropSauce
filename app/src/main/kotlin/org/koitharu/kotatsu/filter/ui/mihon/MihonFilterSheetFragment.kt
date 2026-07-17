package org.koitharu.kotatsu.filter.ui.mihon

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as materialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetFilterMihonBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.showSaveFilterDialog

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
		val filter = FilterCoordinator.require(this)
		binding.buttonSave.setOnClickListener { showSaveFilterDialog(filter) }
		binding.buttonReset.setOnClickListener { viewModel.reset() }
		filter.canSaveFilter.observe(viewLifecycleOwner) {
			binding.buttonSave.isEnabled = it
			binding.buttonReset.isEnabled = it
		}
		viewModel.items.observe(viewLifecycleOwner, adapter)
		// The adapter diffs asynchronously, so listen for the actual data dispatch and re-measure
		// after the RecyclerView lays the new items out.
		adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
			override fun onChanged() = onUpdated()
			override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = onUpdated()
			override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onUpdated()
			override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onUpdated()
			override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = onUpdated()

			private fun onUpdated() {
				viewBinding?.recyclerView?.doOnLayout { adjustHeightToContent() }
			}
		})
		viewModel.isLoading.observe(viewLifecycleOwner) {
			binding.progressBar.isVisible = it
		}
		viewModel.isEmptyState.observe(viewLifecycleOwner) {
			binding.textViewHolder.isVisible = it
		}
		addSheetCallback(this, viewLifecycleOwner)
		binding.layoutBottom.doOnLayout {
			dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.let { sheet ->
				updateLayoutForOffset(sheet)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		setHalfExpanded()
		viewBinding?.root?.doOnLayout { adjustHeightToContent() }
	}

	/**
	 * Shrinks the half-expanded resting height when the filter list is shorter than the default
	 * half-page height, so short filter sets don't leave a large empty area. The sheet stays
	 * draggable to full screen either way.
	 */
	private fun adjustHeightToContent() {
		val sheetDialog = dialog as? BottomSheetDialog ?: return
		val sheet = sheetDialog.findViewById<View>(materialR.id.design_bottom_sheet) ?: return
		val parentHeight = (sheet.parent as? View)?.height ?: return
		if (parentHeight <= 0) {
			return
		}
		val behavior = sheetDialog.behavior
		val desired = wrappedContentHeight()
		behavior.halfExpandedRatio = if (desired == null) {
			HALF_EXPANDED_RATIO
		} else {
			(desired.toFloat() / parentHeight).coerceIn(MIN_HEIGHT_RATIO, HALF_EXPANDED_RATIO)
		}
		if (behavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
			sheet.requestLayout()
		}
	}

	/**
	 * Height the sheet needs to show all filter items without scrolling,
	 * or null if the content doesn't fit (or isn't measurable yet).
	 */
	private fun wrappedContentHeight(): Int? {
		val binding = viewBinding ?: return null
		if (binding.progressBar.isVisible || binding.textViewHolder.isVisible) {
			return null
		}
		val rv = binding.recyclerView
		val lm = rv.layoutManager as? LinearLayoutManager ?: return null
		val itemCount = rv.adapter?.itemCount ?: return null
		if (itemCount == 0 || lm.findLastVisibleItemPosition() < itemCount - 1) {
			return null
		}
		var content = 0
		for (i in 0 until rv.childCount) {
			val child = rv.getChildAt(i)
			val lp = child.layoutParams as ViewGroup.MarginLayoutParams
			content += lm.getDecoratedMeasuredHeight(child) + lp.topMargin + lp.bottomMargin
		}
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		// layoutBottom already carries the navigation-bar inset in its own padding
		return binding.headerBar.height + content + basePadding + binding.layoutBottom.height
	}

	override fun onStateChanged(sheet: View, newState: Int) {
		updateLayoutForOffset(sheet)
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		// A ratio change applied mid-settle doesn't retarget the running animation, so re-check
		// once the sheet comes to rest.
		adjustHeightToContent()
		// Snap the drag handle to its resting state for programmatic moves; manual drags drive it via onSlide.
		viewBinding?.headerBar?.setDragHandleCollapseProgress(if (newState == STATE_EXPANDED) 1f else 0f)
	}

	override fun onSlide(sheet: View, slideOffset: Float) {
		updateLayoutForOffset(sheet)
		// Melt the drag handle away over the top stretch of the drag so reaching full screen is one
		// seamless motion rather than the handle snapping out once expanded.
		val binding = viewBinding ?: return
		val progress = (slideOffset - DRAG_HANDLE_COLLAPSE_START) / (1f - DRAG_HANDLE_COLLAPSE_START)
		binding.headerBar.setDragHandleCollapseProgress(progress)
	}

	private fun updateLayoutForOffset(sheet: View) {
		val binding = viewBinding ?: return
		val top = sheet.top
		binding.layoutBottom.translationY = -top.toFloat()

		val surfaceColor = getSheetSurfaceColor(sheet)
		binding.layoutBottom.setBackgroundColor(surfaceColor)

		// The sheet is match_parent tall, so at rest its bottom (with the button row) hangs below
		// the screen by `top` minus the overlaid button row — padding of basePadding + top is
		// exactly what lets the last item scroll clear of the pinned buttons, with no dead
		// scroll range left over.
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		binding.recyclerView.updatePadding(
			bottom = basePadding + top
		)
	}

	private fun getSheetSurfaceColor(sheet: View): Int {
		val color = when (val background = sheet.background) {
			is MaterialShapeDrawable -> background.fillColor?.defaultColor
			is ColorDrawable -> background.color
			else -> null
		}
		return color ?: requireContext().getThemeColor(android.R.attr.colorBackground)
	}

	private fun SheetFilterMihonBinding.adjustForEmbeddedLayout() {
		root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
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
		dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.let { sheet ->
			updateLayoutForOffset(sheet)
		} ?: run {
			// Embedded layout fallback
			viewBinding?.run {
				val surfaceColor = requireContext().getThemeColor(android.R.attr.colorBackground)
				layoutBottom.setBackgroundColor(surfaceColor)
				recyclerView.updatePadding(bottom = basePadding + barsInsets.bottom + layoutBottom.height)
			}
		}
		return insets.consume(v, typeMask, bottom = true)
	}

	private companion object {
		// Slide offset (0 = half, 1 = full screen) at which the drag handle starts collapsing. Kept above
		// the half-expanded resting offset so the handle stays full at the centre position.
		const val DRAG_HANDLE_COLLAPSE_START = 0.65f

		// Lower bound for the content-fitted sheet height so a couple of filters don't produce a sliver
		const val MIN_HEIGHT_RATIO = 0.2f
	}
}
