package org.koitharu.kotatsu.details.ui.pager

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.pager.bookmarks.BookmarksFragment
import org.koitharu.kotatsu.details.ui.pager.chapters.ChaptersFragment
import org.koitharu.kotatsu.details.ui.pager.pages.PagesFragment

class ChaptersPagesAdapter(
	fragment: Fragment,
	val isPagesTabEnabled: Boolean,
	private val isChaptersOnly: Boolean = false,
) : FragmentStateAdapter(fragment),
	TabLayoutMediator.TabConfigurationStrategy {

	override fun getItemCount(): Int = when {
		isChaptersOnly -> 1
		isPagesTabEnabled -> 3
		else -> 2
	}

	override fun createFragment(position: Int): Fragment = when (position) {
		0 -> ChaptersFragment()
		1 -> if (isPagesTabEnabled) PagesFragment() else BookmarksFragment()
		2 -> BookmarksFragment()
		else -> throw IllegalArgumentException("Invalid position $position")
	}

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		tab.setIcon(
			when (position) {
				0 -> R.drawable.ic_list
				1 -> if (isPagesTabEnabled) R.drawable.ic_grid else R.drawable.ic_bookmark
				2 -> R.drawable.ic_bookmark
				else -> 0
			},
		)
	}
}
