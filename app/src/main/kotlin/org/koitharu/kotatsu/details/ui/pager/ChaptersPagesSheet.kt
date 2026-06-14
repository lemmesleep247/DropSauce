package org.koitharu.kotatsu.details.ui.pager

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import com.google.android.material.R as materialR
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_COLLAPSED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.doOnPageChanged
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.menuView
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.databinding.SheetChaptersPagesBinding
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.ReadButtonDelegate
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import javax.inject.Inject

@AndroidEntryPoint
class ChaptersPagesSheet : BaseAdaptiveSheet<SheetChaptersPagesBinding>(),
	TabLayout.OnTabSelectedListener,
	ActionModeListener,
	AdaptiveSheetCallback {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)

	private val searchBackCallback = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			collapseSearch()
		}
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetChaptersPagesBinding {
		return SheetChaptersPagesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetChaptersPagesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()

		val args = arguments ?: Bundle.EMPTY
		var defaultTab = args.getInt(AppRouter.KEY_TAB, settings.defaultDetailsTab)
		val adapter = ChaptersPagesAdapter(this, settings.isPagesTabEnabled)
		if (!adapter.isPagesTabEnabled) {
			defaultTab = (defaultTab - 1).coerceAtLeast(TAB_CHAPTERS)
		}
		(viewModel as? DetailsViewModel)?.let { dvm ->
			ReadButtonDelegate(binding.splitButtonRead, dvm, router).attach(viewLifecycleOwner)
		}
		binding.pager.offscreenPageLimit = adapter.itemCount
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		binding.pager.adapter = adapter
		binding.pager.doOnPageChanged(::onPageChanged)
		TabLayoutMediator(binding.tabs, binding.pager, adapter).attach()
		binding.tabs.addOnTabSelectedListener(this)
		binding.pager.setCurrentItem(defaultTab, false)
		binding.tabs.isVisible = adapter.itemCount > 1

		val menuProvider = ChapterPagesMenuProvider(viewModel, this, binding.pager, settings)
		onBackPressedDispatcher.addCallback(viewLifecycleOwner, menuProvider)
		binding.toolbar.addMenuProvider(menuProvider)
		onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)
		setupSearch(binding)
		updateSearchVisibility()

		val menuInvalidator = MenuInvalidator(binding.toolbar)
		viewModel.isChaptersReversed.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isChaptersInGridView.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isDownloadedOnly.observe(viewLifecycleOwner, menuInvalidator)

		actionModeDelegate?.addListener(this, viewLifecycleOwner)
		addSheetCallback(this, viewLifecycleOwner)

		viewModel.newChaptersCount.observe(viewLifecycleOwner, ::onNewChaptersChanged)
		viewModel.accentColor.observe(viewLifecycleOwner) { color ->
			if (color != null) applyAccentColor(color)
		}
		if (dialog != null) {
			viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
			viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
			viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.pager))
		} else {
			PeekHeightController(arrayOf(binding.headerBar, binding.toolbar)).attach()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onStateChanged(sheet: View, newState: Int) {
		val binding = viewBinding ?: return
		binding.layoutTouchBlock.isTouchEventsAllowed = dialog != null || newState != STATE_COLLAPSED
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		val isActionModeStarted = actionModeDelegate?.isActionModeStarted == true
		binding.toolbar.menuView?.isVisible = newState == STATE_EXPANDED && !isActionModeStarted
		binding.splitButtonRead.isVisible = newState != STATE_EXPANDED && !isActionModeStarted
			&& viewModel is DetailsViewModel
		updateSearchVisibility()
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.toolbar?.menuView?.isVisible = false
		updateSearchVisibility()
		view?.post(::expandAndLock)
	}

	override fun onActionModeFinished(mode: ActionMode) {
		unlock()
		val state = behavior?.state ?: STATE_EXPANDED
		viewBinding?.toolbar?.menuView?.isVisible = state != STATE_COLLAPSED
		updateSearchVisibility()
	}

	override fun onTabSelected(tab: TabLayout.Tab?) = Unit

	override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

	override fun onTabReselected(tab: TabLayout.Tab?) {
		val f = childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return,
		) as? RecyclerViewOwner ?: return
		f.recyclerView?.smoothScrollToTop()
	}

	override fun expandAndLock() {
		super.expandAndLock()
		adjustLockState()
	}

	override fun unlock() {
		super.unlock()
		adjustLockState()
	}

	private fun adjustLockState() {
		viewBinding?.run {
			pager.isUserInputEnabled = !isLocked
			tabs.visibility = when {
				(pager.adapter?.itemCount ?: 0) <= 1 -> View.GONE
				isLocked -> View.INVISIBLE
				else -> View.VISIBLE
			}
		}
	}

	private fun onPageChanged(position: Int) {
		viewBinding?.toolbar?.invalidateMenu()
		settings.lastDetailsTab = position
		updateSearchVisibility()
	}

	private fun setupSearch(binding: SheetChaptersPagesBinding) {
		with(binding.searchView) {
			queryHint = getString(R.string.search_chapters)
			setOnQueryTextListener(object : SearchView.OnQueryTextListener {
				override fun onQueryTextSubmit(query: String?): Boolean = false

				override fun onQueryTextChange(newText: String?): Boolean {
					viewModel.performChapterSearch(newText)
					return true
				}
			})
			setOnSearchClickListener {
				setSearchFieldExpanded(true)
				searchBackCallback.isEnabled = true
			}
			setOnCloseListener {
				setSearchFieldExpanded(false)
				viewModel.performChapterSearch(null)
				searchBackCallback.isEnabled = false
				false
			}
		}
	}

	// The search lives only on the Chapters tab and only while the bar is fully pulled up.
	private fun updateSearchVisibility() {
		val binding = viewBinding ?: return
		val isActionModeStarted = actionModeDelegate?.isActionModeStarted == true
		val isExpanded = dialog != null || behavior?.state == STATE_EXPANDED
		val show = isExpanded && !isActionModeStarted && binding.pager.currentItem == TAB_CHAPTERS
		if (binding.searchView.isVisible != show) {
			binding.searchView.isVisible = show
		}
		if (!show && !binding.searchView.isIconified) {
			collapseSearch()
		}
	}

	// Collapsed: the search is a single icon at the end of the bar (tabs keep the weight). Expanded:
	// the field takes the empty space to the right of the tabs (the weights are swapped).
	private fun setSearchFieldExpanded(expanded: Boolean) {
		val binding = viewBinding ?: return
		binding.tabs.updateLayoutParams<LinearLayout.LayoutParams> {
			width = if (expanded) LinearLayout.LayoutParams.WRAP_CONTENT else 0
			weight = if (expanded) 0f else 1f
		}
		binding.searchView.updateLayoutParams<LinearLayout.LayoutParams> {
			width = if (expanded) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
			weight = if (expanded) 1f else 0f
		}
	}

	private fun collapseSearch() {
		val searchView = viewBinding?.searchView ?: return
		searchView.setQuery("", false)
		if (!searchView.isIconified) {
			searchView.isIconified = true
		}
	}

	// Recolor the Read/Continue split button to the cover accent so the sheet matches the page.
	private fun applyAccentColor(@androidx.annotation.ColorInt color: Int) {
		val binding = viewBinding ?: return
		val onAccent = if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
		val accentTint = ColorStateList.valueOf(color)
		val onAccentTint = ColorStateList.valueOf(onAccent)
		// The selected page-tab (chapters / pages / bookmarks) wrapper picks up a tonal accent so it
		// matches the rest of the page while keeping the icon legible.
		val surface = MaterialColors.getColor(binding.tabs, materialR.attr.colorSurfaceContainerHighest, Color.GRAY)
		binding.tabs.setSelectedTabIndicatorColor(ColorUtils.blendARGB(surface, color, 0.5f))
		(binding.splitButtonRead[0] as? MaterialButton)?.apply {
			backgroundTintList = accentTint
			setTextColor(onAccent)
			iconTint = onAccentTint
		}
		(binding.splitButtonRead[1] as? MaterialButton)?.apply {
			backgroundTintList = accentTint
			iconTint = onAccentTint
		}
	}

	private fun onNewChaptersChanged(counter: Int) {
		val tab = viewBinding?.tabs?.getTabAt(0) ?: return
		if (counter == 0) {
			tab.removeBadge()
		} else {
			val badge = tab.orCreateBadge
			badge.number = counter
		}
	}

	companion object {

		const val TAB_CHAPTERS = 0
		const val TAB_PAGES = 1
		const val TAB_BOOKMARKS = 2
	}
}
