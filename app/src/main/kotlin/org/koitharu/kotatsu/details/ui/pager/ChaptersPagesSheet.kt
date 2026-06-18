package org.koitharu.kotatsu.details.ui.pager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_COLLAPSED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.applyTonalActionMenuStyle
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

	// Tracks the current drag-handle visibility so the show/hide is only animated when it actually flips.
	private var dragHandleShown = true

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
		// The read/continue action now lives in the details page's floating FAB, so the sheet's own
		// split button is retired here to avoid a duplicate control.
		binding.splitButtonRead.isVisible = false
		binding.pager.offscreenPageLimit = adapter.itemCount
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		binding.pager.adapter = adapter
		binding.pager.doOnPageChanged(::onPageChanged)
		TabLayoutMediator(binding.tabs, binding.pager, adapter).attach()
		binding.tabs.addOnTabSelectedListener(this)
		binding.pager.setCurrentItem(defaultTab, false)
		binding.tabs.isVisible = adapter.itemCount > 1

		val menuProvider = ChapterPagesMenuProvider(this, binding.pager, settings, viewModel, binding.layoutToolbarContent)
		onBackPressedDispatcher.addCallback(viewLifecycleOwner, menuProvider)
		binding.toolbar.addMenuProvider(menuProvider)
		// Group the search + overflow actions into the same tonal pill the rest of the app's top bars use.
		binding.toolbar.applyTonalActionMenuStyle()

		val menuInvalidator = MenuInvalidator(binding.toolbar)
		viewModel.isChaptersReversed.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isChaptersInGridView.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isDownloadedOnly.observe(viewLifecycleOwner, menuInvalidator)

		actionModeDelegate?.addListener(this, viewLifecycleOwner)
		addSheetCallback(this, viewLifecycleOwner)

		viewModel.newChaptersCount.observe(viewLifecycleOwner, ::onNewChaptersChanged)
		if (dialog != null) {
			viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
			viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
			viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.pager))
		} else {
			PeekHeightController(arrayOf(binding.headerBar, binding.toolbar)).attach()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onStart() {
		super.onStart()
		// In the details screen the sheet is a modal that rises to a centred, half-expanded position.
		// From there it can be dragged up to full screen or swiped straight down to dismiss (no peek
		// stop in between). The reader hosts the same sheet but keeps its own behaviour, so this is
		// gated to the DetailsViewModel context.
		if (viewModel is DetailsViewModel) {
			(dialog as? BottomSheetDialog)?.behavior?.apply {
				isFitToContents = false
				isHideable = true
				skipCollapsed = true
				halfExpandedRatio = HALF_EXPANDED_RATIO
				state = BottomSheetBehavior.STATE_HALF_EXPANDED
			}
		}
	}

	override fun onStateChanged(sheet: View, newState: Int) {
		val binding = viewBinding ?: return
		binding.layoutTouchBlock.isTouchEventsAllowed = dialog != null || newState != STATE_COLLAPSED
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		val isActionModeStarted = actionModeDelegate?.isActionModeStarted == true
		// The search + list-options pill is available whenever the sheet is on screen as a modal (it opens
		// at the centre position) or fully expanded — it only hides during selection action mode.
		val isModalOrExpanded = dialog != null || newState == STATE_EXPANDED
		binding.toolbar.menuView?.isVisible = isModalOrExpanded && !isActionModeStarted
		// The drag handle is the grab affordance for the floating/centre state; at full screen the sheet
		// behaves like a normal top-level screen, so the handle is dropped and the toolbar sits directly
		// under the status bar like the rest of the app. Animate the swap so docking to full screen
		// doesn't snap the toolbar upward.
		val showHandle = newState != STATE_EXPANDED
		if (dragHandleShown != showHandle) {
			dragHandleShown = showHandle
			TransitionManager.beginDelayedTransition(binding.root as ViewGroup, AutoTransition().setDuration(200))
			binding.headerBar.setDragHandleVisible(showHandle)
		}
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.toolbar?.menuView?.isVisible = false
		view?.post(::expandAndLock)
	}

	override fun onActionModeFinished(mode: ActionMode) {
		unlock()
		val state = behavior?.state ?: STATE_EXPANDED
		viewBinding?.toolbar?.menuView?.isVisible = state != STATE_COLLAPSED
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

		// How much of the screen height the sheet covers when it first opens at the centre position.
		private const val HALF_EXPANDED_RATIO = 0.62f
	}
}
