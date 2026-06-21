package org.koitharu.kotatsu.search.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.model.parcelable.ParcelableMangaListFilter
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.model.titleRes
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.util.ViewBadge
import org.koitharu.kotatsu.core.util.ext.buildBundle
import org.koitharu.kotatsu.core.util.ext.consumeSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.getSerializableExtraCompat
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityMangaListBinding
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.FilterHeaderFragment
import org.koitharu.kotatsu.filter.ui.mihon.MihonFilterSheetFragment
import org.koitharu.kotatsu.filter.ui.sheet.FilterSheetFragment
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.list.ui.preview.PreviewFragment
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.remotelist.ui.RemoteListFragment
import kotlin.math.absoluteValue
import com.google.android.material.R as materialR

@AndroidEntryPoint
class MangaListActivity :
	BaseActivity<ActivityMangaListBinding>(),
	AppBarOwner, View.OnClickListener,
	FilterCoordinator.Owner,
	AppBarLayout.OnOffsetChangedListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val filterCoordinator: FilterCoordinator
		get() = checkNotNull(findFilterOwner()) {
			"Cannot find FilterCoordinator.Owner fragment in ${supportFragmentManager.fragments}"
		}.filterCoordinator

	@javax.inject.Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	private lateinit var source: MangaSource

	// The active language's native name, appended after the source name in the top bar.
	private var activeLanguageName: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityMangaListBinding.inflate(layoutInflater))
		viewBinding.collapsingToolbarLayout?.let { collapsingToolbarLayout ->
			FadingAppbarMediator(viewBinding.appbar, collapsingToolbarLayout).bind()
		}
		val filter = intent.getParcelableExtraCompat<ParcelableMangaListFilter>(AppRouter.KEY_FILTER)?.filter
		val sortOrder = intent.getSerializableExtraCompat<SortOrder>(AppRouter.KEY_SORT_ORDER)
		// Collapse multi-language sources to their active variant so the screen always reflects the
		// language chosen in source settings, even if it was opened via a different variant.
		val resolved = sourcesRepository.resolveActiveSource(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)))
		source = resolved.source
		activeLanguageName = resolved.languageSubtitle
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		if (viewBinding.containerFilterHeader != null) {
			viewBinding.appbar.addOnOffsetChangedListener(this)
		}
		viewBinding.buttonOrder?.setOnClickListener(this)
		applyTitle()
		initList(source, filter, sortOrder)
	}

	override fun onResume() {
		super.onResume()
		// If the active language was changed in source settings, reload the list in place for the new
		// variant so the user doesn't have to back out to Explore and reopen the extension.
		val resolved = sourcesRepository.resolveActiveSource(source)
		if (resolved.source.name != source.name) {
			source = resolved.source
			activeLanguageName = resolved.languageSubtitle
			applyTitle()
			reloadList(source)
		}
	}

	/**
	 * Shows the source name with the active language appended after it on the same line, but kept
	 * smaller and muted (the same size it had as a subheader).
	 */
	private fun applyTitle() {
		val name = source.getTitle(this)
		val lang = activeLanguageName
		val titleText: CharSequence = if (lang.isNullOrEmpty()) {
			name
		} else {
			SpannableStringBuilder(name).apply {
				append("  ")
				val start = length
				append(lang)
				setSpan(RelativeSizeSpan(0.7f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
				setSpan(
					ForegroundColorSpan(getThemeColor(materialR.attr.colorOnSurfaceVariant)),
					start,
					length,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
				)
			}
		}
		title = titleText
		viewBinding.collapsingToolbarLayout?.let {
			it.title = titleText
			it.subtitle = null
		}
	}

	/**
	 * Replaces the list (and filter header/side) with a fresh fragment for [newSource]. A plain
	 * recreate() can't be used here: the FragmentManager would restore the previous source's list
	 * fragment, so only the title would change while the content stayed on the old language.
	 */
	private fun reloadList(newSource: MangaSource) {
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container, RemoteListFragment.newInstance(newSource))
			// The filter header/side capture their FilterCoordinator at creation, so recreate them
			// too — otherwise they keep driving the old source's list.
			if (viewBinding.containerFilterHeader != null) {
				replace(R.id.container_filter_header, FilterHeaderFragment::class.java, null)
			}
			if (viewBinding.containerSide != null) {
				// reloadList only fires for remote (Mihon) multi-language sources, which are dynamic.
				replace(R.id.container_side, MihonFilterSheetFragment::class.java, null)
			}
			runOnCommit { findFilterOwner()?.let { initFilter(it) } }
		}
	}

	override fun isNsfwContent(): Flow<Boolean> = flowOf(source.isNsfw())

	override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
		val container = viewBinding.containerFilterHeader ?: return
		container.background = if (verticalOffset.absoluteValue < appBarLayout.totalScrollRange) {
			container.context.getThemeColor(materialR.attr.backgroundColor).toDrawable()
		} else {
			viewBinding.collapsingToolbarLayout?.contentScrim
		}
	}

	/**
	 * Only for landscape
	 */
	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.cardSide?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginEnd = barsInsets.end(v) + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
			topMargin = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer_double)
			bottomMargin = barsInsets.bottom + resources.getDimensionPixelOffset(R.dimen.side_card_offset)
		}
		viewBinding.appbar.updatePaddingRelative(
			top = barsInsets.top,
			end = if (viewBinding.cardSide == null) barsInsets.end(v) else 0,
			start = barsInsets.start(v),
		)
		return insets.consumeSystemBarsInsets(v, top = true, end = true)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_order -> router.showSortSheet()
		}
	}

	fun showPreview(manga: Manga): Boolean = setSideFragment(
		PreviewFragment::class.java,
		buildBundle(1) {
			putParcelable(AppRouter.KEY_MANGA, ParcelableManga(manga))
		},
	)

	fun hidePreview() = setSideFragment(filterSheetClass(findFilterOwner()), null)

	private fun filterSheetClass(owner: FilterCoordinator.Owner?): Class<out Fragment> =
		if (owner?.filterCoordinator?.isDynamicFilter == true) {
			MihonFilterSheetFragment::class.java
		} else {
			FilterSheetFragment::class.java
		}

	private fun initList(source: MangaSource, filter: MangaListFilter?, sortOrder: SortOrder?) {
		val fm = supportFragmentManager
		val existingFragment = fm.findFragmentById(R.id.container)
		if (existingFragment is FilterCoordinator.Owner) {
			initFilter(existingFragment)
		} else {
			fm.commit {
				setReorderingAllowed(true)
				val fragment = if (source == LocalMangaSource) {
					LocalListFragment()
				} else {
					RemoteListFragment.newInstance(source)
				}
				replace(R.id.container, fragment)
				runOnCommit { initFilter(fragment) }
				if (filter != null || sortOrder != null) {
					runOnCommit(ApplyFilterRunnable(fragment, filter, sortOrder))
				}
			}
		}
	}

	private fun initFilter(filterOwner: FilterCoordinator.Owner) {
		if (viewBinding.containerSide != null) {
			if (supportFragmentManager.findFragmentById(R.id.container_side) == null) {
				setSideFragment(filterSheetClass(filterOwner), null)
			}
		} else if (viewBinding.containerFilterHeader != null) {
			if (supportFragmentManager.findFragmentById(R.id.container_filter_header) == null) {
				supportFragmentManager.commit {
					setReorderingAllowed(true)
					replace(R.id.container_filter_header, FilterHeaderFragment::class.java, null)
				}
			}
		}
		val filter = filterOwner.filterCoordinator
		val chipSort = viewBinding.buttonOrder
		if (chipSort != null) {
			val filterBadge = ViewBadge(chipSort, this)
			filterBadge.setMaxCharacterCount(0)
			val isDynamic = filter.isDynamicFilter
			filter.observe().observe(this) { snapshot ->
				if (isDynamic) {
					// The real sort lives inside the Mihon FilterList (encoded as a "srt@" tag); show its
					// value on the button and only count non-sort tags towards the "filter applied" badge.
					val sortTag = snapshot.listFilter.tags.firstOrNull { it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX) }
					chipSort.text = sortTag?.title?.substringAfter(": ") ?: getString(snapshot.sortOrder.titleRes)
					chipSort.isVisible = true
					filterBadge.counter = if (snapshot.listFilter.tags.any { !it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX) }) 1 else 0
				} else {
					chipSort.setTextAndVisible(snapshot.sortOrder.titleRes)
					filterBadge.counter = if (snapshot.listFilter.hasNonSearchOptions()) 1 else 0
				}
			}
		} else {
			filter.observe().map {
				it.listFilter.getSummary()
			}.flowOn(Dispatchers.Default)
				.observe(this) {
					supportActionBar?.subtitle = it
				}
		}
	}

	private fun findFilterOwner(): FilterCoordinator.Owner? {
		return supportFragmentManager.findFragmentById(R.id.container) as? FilterCoordinator.Owner
	}

	private fun setSideFragment(cls: Class<out Fragment>, args: Bundle?) = if (viewBinding.containerSide != null) {
		supportFragmentManager.commit {
			setReorderingAllowed(true)
			replace(R.id.container_side, cls, args)
		}
		true
	} else {
		false
	}

	private class ApplyFilterRunnable(
		private val filterOwner: FilterCoordinator.Owner,
		private val filter: MangaListFilter?,
		private val sortOrder: SortOrder?,
	) : Runnable {

		override fun run() {
			if (sortOrder != null) {
				filterOwner.filterCoordinator.setSortOrder(sortOrder)
			}
			if (filter != null) {
				filterOwner.filterCoordinator.setAdjusted(filter)
			}
		}
	}
}
