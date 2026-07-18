package org.koitharu.kotatsu.filter.ui.sheet

import android.os.Bundle
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import com.google.android.material.R as materialR
import com.google.android.material.chip.Chip
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.shape.MaterialShapeDrawable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.model.titleRes
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.parentView
import org.koitharu.kotatsu.core.util.ext.setValueRounded
import org.koitharu.kotatsu.core.util.ext.setValuesRounded
import org.koitharu.kotatsu.databinding.SheetFilterBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.showSaveFilterDialog
import org.koitharu.kotatsu.filter.ui.model.FilterProperty
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.util.toIntUp
import java.util.Locale

class FilterSheetFragment : BaseAdaptiveSheet<SheetFilterBinding>(),
    AdapterView.OnItemSelectedListener,
    View.OnClickListener,
    AdaptiveSheetCallback,
    ChipsView.OnChipClickListener {

    private var systemBarsBottom = 0

    override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetFilterBinding {
        return SheetFilterBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(binding: SheetFilterBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        if (dialog == null) {
            binding.adjustForEmbeddedLayout()
        }
        val filter = FilterCoordinator.require(this)
        filter.sortOrder.observe(viewLifecycleOwner, this::onSortOrderChanged)
        filter.locale.observe(viewLifecycleOwner, this::onLocaleChanged)
        filter.originalLocale.observe(viewLifecycleOwner, this::onOriginalLocaleChanged)
        filter.tags.observe(viewLifecycleOwner, this::onTagsChanged)
        filter.tagsExcluded.observe(viewLifecycleOwner, this::onTagsExcludedChanged)
        filter.authors.observe(viewLifecycleOwner, this::onAuthorsChanged)
        filter.states.observe(viewLifecycleOwner, this::onStateChanged)
        filter.contentTypes.observe(viewLifecycleOwner, this::onContentTypesChanged)
        filter.contentRating.observe(viewLifecycleOwner, this::onContentRatingChanged)
        filter.demographics.observe(viewLifecycleOwner, this::onDemographicsChanged)
        filter.year.observe(viewLifecycleOwner, this::onYearChanged)
        filter.yearRange.observe(viewLifecycleOwner, this::onYearRangeChanged)
        binding.layoutGenres.setTitle(
            if (filter.capabilities.isMultipleTagsSupported) {
                R.string.genres
            } else {
                R.string.genre
            },
        )
        binding.spinnerLocale.onItemSelectedListener = this
        binding.spinnerOriginalLocale.onItemSelectedListener = this
        binding.spinnerOrder.onItemSelectedListener = this
        binding.chipsState.onChipClickListener = this
        binding.chipsTypes.onChipClickListener = this
        binding.chipsContentRating.onChipClickListener = this
        binding.chipsDemographics.onChipClickListener = this
        binding.chipsGenres.onChipClickListener = this
        binding.chipsGenresExclude.onChipClickListener = this
        binding.chipsAuthor.onChipClickListener = this
        binding.sliderYear.addOnChangeListener(this::onSliderValueChange)
        binding.sliderYearsRange.addOnChangeListener(this::onRangeSliderValueChange)
        binding.layoutGenres.setOnMoreButtonClickListener {
            router.showTagsCatalogSheet(excludeMode = false)
        }
        binding.layoutGenresExclude.setOnMoreButtonClickListener {
            router.showTagsCatalogSheet(excludeMode = true)
        }
        filter.canSaveFilter.observe(viewLifecycleOwner) {
            binding.buttonSave.isEnabled = it
            binding.buttonReset.isEnabled = it
        }
        binding.buttonSave.setOnClickListener(this)
        binding.buttonReset.setOnClickListener(this)
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
    }

    override fun onStateChanged(sheet: View, newState: Int) {
        updateLayoutForOffset(sheet)
        if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
            return
        }
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

        val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
        val buttonsHeight = binding.layoutBottom.height
        binding.scrollView.updatePadding(
            bottom = basePadding + systemBarsBottom + buttonsHeight + top
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

    private fun SheetFilterBinding.adjustForEmbeddedLayout() {
        layoutBody.updatePadding(top = layoutBody.paddingBottom)
        scrollView.scrollIndicators = 0
        this.root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val typeMask = WindowInsetsCompat.Type.systemBars()
        val barsInsets = insets.getInsets(typeMask)
        systemBarsBottom = barsInsets.bottom
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
                scrollView.updatePadding(bottom = basePadding + barsInsets.bottom + layoutBottom.height)
            }
        }
        return insets.consume(v, typeMask, bottom = true)
    }

    override fun onClick(v: View) {
        val filter = FilterCoordinator.require(this)
        when (v.id) {
            R.id.button_save -> showSaveFilterDialog(filter)
            R.id.button_reset -> filter.clearSavedFilter()
        }
    }

    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
        val filter = FilterCoordinator.require(this)
        when (parent.id) {
            R.id.spinner_order -> filter.setSortOrder(filter.sortOrder.value.availableItems[position])
            R.id.spinner_locale -> filter.setLocale(filter.locale.value.availableItems[position])
            R.id.spinner_original_locale -> filter.setOriginalLocale(filter.originalLocale.value.availableItems[position])
        }
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit

    private fun onSliderValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        if (!fromUser) {
            return
        }
        val intValue = value.toInt()
        val filter = FilterCoordinator.require(this)
        when (slider.id) {
            R.id.slider_year -> filter.setYear(
                if (intValue <= slider.valueFrom.toIntUp()) {
                    YEAR_UNKNOWN
                } else {
                    intValue
                },
            )
        }
    }

    private fun onRangeSliderValueChange(slider: RangeSlider, value: Float, fromUser: Boolean) {
        if (!fromUser) {
            return
        }
        val filter = FilterCoordinator.require(this)
        when (slider.id) {
            R.id.slider_yearsRange -> filter.setYearRange(
                valueFrom = slider.values.firstOrNull()?.let {
                    if (it <= slider.valueFrom) YEAR_UNKNOWN else it.toInt()
                } ?: YEAR_UNKNOWN,
                valueTo = slider.values.lastOrNull()?.let {
                    if (it >= slider.valueTo) YEAR_UNKNOWN else it.toInt()
                } ?: YEAR_UNKNOWN,
            )
        }
    }

    override fun onChipClick(chip: Chip, data: Any?) {
        val filter = FilterCoordinator.require(this)
        when (data) {
            is MangaState -> filter.toggleState(data, !chip.isChecked)
            is MangaTag -> if (chip.parentView?.id == R.id.chips_genresExclude) {
                filter.toggleTagExclude(data, !chip.isChecked)
            } else {
                filter.toggleTag(data, !chip.isChecked)
            }

            is ContentType -> filter.toggleContentType(data, !chip.isChecked)
            is ContentRating -> filter.toggleContentRating(data, !chip.isChecked)
            is Demographic -> filter.toggleDemographic(data, !chip.isChecked)
            is String -> if (chip.isChecked) {
                filter.setAuthor(null)
            } else {
                filter.setAuthor(data)
            }
            null -> router.showTagsCatalogSheet(excludeMode = chip.parentView?.id == R.id.chips_genresExclude)
        }
    }

    private fun onSortOrderChanged(value: FilterProperty<SortOrder>) {
        val b = viewBinding ?: return
        b.layoutOrder.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val selected = value.selectedItems.single()
        b.spinnerOrder.adapter = ArrayAdapter(
            b.spinnerOrder.context,
            android.R.layout.simple_spinner_dropdown_item,
            android.R.id.text1,
            value.availableItems.map { b.spinnerOrder.context.getString(it.titleRes) },
        )
        val selectedIndex = value.availableItems.indexOf(selected)
        if (selectedIndex >= 0) {
            b.spinnerOrder.setSelection(selectedIndex, false)
        }
    }

    private fun onLocaleChanged(value: FilterProperty<Locale?>) {
        val b = viewBinding ?: return
        b.layoutLocale.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val selected = value.selectedItems.singleOrNull()
        b.spinnerLocale.adapter = ArrayAdapter(
            b.spinnerLocale.context,
            android.R.layout.simple_spinner_dropdown_item,
            android.R.id.text1,
            value.availableItems.map { it.getDisplayName(b.spinnerLocale.context) },
        )
        val selectedIndex = value.availableItems.indexOf(selected)
        if (selectedIndex >= 0) {
            b.spinnerLocale.setSelection(selectedIndex, false)
        }
    }

    private fun onOriginalLocaleChanged(value: FilterProperty<Locale?>) {
        val b = viewBinding ?: return
        b.layoutOriginalLocale.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val selected = value.selectedItems.singleOrNull()
        b.spinnerOriginalLocale.adapter = ArrayAdapter(
            b.spinnerOriginalLocale.context,
            android.R.layout.simple_spinner_dropdown_item,
            android.R.id.text1,
            value.availableItems.map { it.getDisplayName(b.spinnerOriginalLocale.context) },
        )
        val selectedIndex = value.availableItems.indexOf(selected)
        if (selectedIndex >= 0) {
            b.spinnerOriginalLocale.setSelection(selectedIndex, false)
        }
    }

    private fun onTagsChanged(value: FilterProperty<MangaTag>) {
        val b = viewBinding ?: return
        b.layoutGenres.isGone = value.isEmptyAndSuccess()
        b.layoutGenres.setError(value.error?.getDisplayMessage(resources))
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { tag ->
            ChipsView.ChipModel(
                title = tag.title,
                isChecked = tag in value.selectedItems,
                data = tag,
            )
        }
        b.chipsGenres.setChips(chips)
    }

    private fun onTagsExcludedChanged(value: FilterProperty<MangaTag>) {
        val b = viewBinding ?: return
        b.layoutGenresExclude.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { tag ->
            ChipsView.ChipModel(
                title = tag.title,
                isChecked = tag in value.selectedItems,
                data = tag,
            )
        }
        b.chipsGenresExclude.setChips(chips)
    }

    private fun onAuthorsChanged(value: FilterProperty<String>) {
        val b = viewBinding ?: return
        b.layoutAuthor.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { author ->
            ChipsView.ChipModel(
                title = author,
                isChecked = author in value.selectedItems,
                data = author,
            )
        }
        b.chipsAuthor.setChips(chips)
    }

    private fun onStateChanged(value: FilterProperty<MangaState>) {
        val b = viewBinding ?: return
        b.layoutState.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { state ->
            ChipsView.ChipModel(
                title = getString(state.titleResId),
                isChecked = state in value.selectedItems,
                data = state,
            )
        }
        b.chipsState.setChips(chips)
    }

    private fun onContentTypesChanged(value: FilterProperty<ContentType>) {
        val b = viewBinding ?: return
        b.layoutTypes.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { type ->
            ChipsView.ChipModel(
                title = getString(type.titleResId),
                isChecked = type in value.selectedItems,
                data = type,
            )
        }
        b.chipsTypes.setChips(chips)
    }

    private fun onContentRatingChanged(value: FilterProperty<ContentRating>) {
        val b = viewBinding ?: return
        b.layoutContentRating.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { contentRating ->
            ChipsView.ChipModel(
                title = getString(contentRating.titleResId),
                isChecked = contentRating in value.selectedItems,
                data = contentRating,
            )
        }
        b.chipsContentRating.setChips(chips)
    }

    private fun onDemographicsChanged(value: FilterProperty<Demographic>) {
        val b = viewBinding ?: return
        b.layoutDemographics.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val chips = value.availableItems.map { demographic ->
            ChipsView.ChipModel(
                title = getString(demographic.titleResId),
                isChecked = demographic in value.selectedItems,
                data = demographic,
            )
        }
        b.chipsDemographics.setChips(chips)
    }

    private fun onYearChanged(value: FilterProperty<Int>) {
        val b = viewBinding ?: return
        b.layoutYear.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        val currentValue = value.selectedItems.singleOrNull() ?: YEAR_UNKNOWN
        b.layoutYear.setValueText(
            if (currentValue == YEAR_UNKNOWN) {
                getString(R.string.any)
            } else {
                currentValue.toString()
            },
        )
        b.sliderYear.valueFrom = value.availableItems.first().toFloat()
        b.sliderYear.valueTo = value.availableItems.last().toFloat()
        b.sliderYear.setValueRounded(currentValue.toFloat())
    }

    private fun onYearRangeChanged(value: FilterProperty<Int>) {
        val b = viewBinding ?: return
        b.layoutYearsRange.isGone = value.isEmpty()
        if (value.isEmpty()) {
            return
        }
        b.sliderYearsRange.valueFrom = value.availableItems.first().toFloat()
        b.sliderYearsRange.valueTo = value.availableItems.last().toFloat()
        val currentValueFrom = value.selectedItems.firstOrNull()?.toFloat() ?: b.sliderYearsRange.valueFrom
        val currentValueTo = value.selectedItems.lastOrNull()?.toFloat() ?: b.sliderYearsRange.valueTo
        b.layoutYearsRange.setValueText(
            getString(
                R.string.memory_usage_pattern,
                currentValueFrom.toInt().toString(),
                currentValueTo.toInt().toString(),
            ),
        )
        b.sliderYearsRange.setValuesRounded(currentValueFrom, currentValueTo)
    }

    private companion object {
        // Slide offset (0 = half, 1 = full screen) at which the drag handle starts collapsing. Kept above
        // the half-expanded resting offset so the handle stays full at the centre position.
        const val DRAG_HANDLE_COLLAPSE_START = 0.65f
    }
}
