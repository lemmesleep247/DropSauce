package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBar
import org.koitharu.kotatsu.main.ui.nav.FloatingNavBarItem
import org.koitharu.kotatsu.main.ui.nav.composeColorSchemeFromTheme

/**
 * A SlidingBottomNavigationView whose visible face is rendered with Jetpack Compose as a
 * floating, pill-shaped toolbar (inspired by the Tomato app's HorizontalFloatingToolbar).
 *
 * It still owns the underlying NavigationBarView menu, so MainNavigationDelegate keeps
 * driving it through the standard menu / selectedItemId / listener APIs. The inherited
 * NavigationBarMenuView is hidden, and a ComposeView sibling renders the floating bar on top.
 */
class FloatingBottomNavigationView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : SlidingBottomNavigationView(context, attrs) {

	private val composeItemsState = MutableStateFlow<List<FloatingNavBarItem>>(emptyList())
	private val selectedIdState = MutableStateFlow(0)
	private val labeledState = MutableStateFlow(true)
	private val amoledState = MutableStateFlow(false)
	private val sourceItems = mutableListOf<NavItem>()
	private val hiddenIds = mutableSetOf<Int>()
	private val badgeCounts = mutableMapOf<Int, Int>()

	private val composeView: ComposeView = ComposeView(context).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			// DropSauceTheme bakes in both the gflex variable font typography and the
			// activity's color scheme — gives the nav bar the same look as the rest of the app.
			org.koitharu.kotatsu.settings.compose.DropSauceTheme {
				val items by composeItemsState.collectAsState()
				val selectedId by selectedIdState.collectAsState()
				val labeled by labeledState.collectAsState()
				val amoled by amoledState.collectAsState()
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 12.dp, vertical = 8.dp),
					contentAlignment = Alignment.Center,
				) {
					FloatingNavBar(
						items = items,
						selectedId = selectedId,
						showLabels = labeled,
						amoled = amoled,
						onItemSelected = { id -> this@FloatingBottomNavigationView.selectedItemId = id },
						onItemReselected = { id ->
							val menuItem = menu.findItem(id) ?: return@FloatingNavBar
							reselectedListener?.invoke(menuItem)
						},
						modifier = Modifier.wrapContentWidth(),
					)
				}
			}
		}
	}

	private var reselectedListener: ((android.view.MenuItem) -> Unit)? = null

	init {
		// The parent NavigationBarView paints a solid surface — clear it so the Compose pill floats.
		background = null
		elevation = 0f
		// Hide everything the parent NavigationBarView added (the native menu view) BEFORE
		// our composeView gets added — we can't reference BottomNavigationMenuView directly
		// because it's @RestrictTo(LIBRARY_GROUP), so we identify it by exclusion instead.
		hideNativeChildren()
		addView(
			composeView,
			ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			),
		)
	}

	override val maxItemCountOverride: Int
		get() = MAX_RENDERED_ITEMS

	override fun setSelectedItemId(@IdRes itemId: Int) {
		super.setSelectedItemId(itemId)
		selectedIdState.value = selectedItemId
	}

	override fun setOnItemReselectedListener(listener: OnItemReselectedListener?) {
		super.setOnItemReselectedListener(listener)
		reselectedListener = listener?.let { l -> { item -> l.onNavigationItemReselected(item) } }
	}

	/**
	 * Maximum number of items the floating bar will render. Extra items are dropped so the
	 * pill bar stays comfortable on phones — the rest remain accessible via overflow / settings.
	 */
	val maxRenderedItems: Int = MAX_RENDERED_ITEMS

	/**
	 * Set the list of nav items currently configured in settings. The bar renders up to
	 * [maxRenderedItems] of them, skipping any temporarily hidden via [setComposeItemVisibility].
	 */
	fun setComposeItems(items: List<NavItem>) {
		sourceItems.clear()
		sourceItems.addAll(items)
		rebuildComposeItems()
	}

	fun setComposeLabeled(value: Boolean) {
		labeledState.value = value
	}

	fun setComposeAmoled(value: Boolean) {
		amoledState.value = value
	}

	fun setComposeBadge(@IdRes itemId: Int, count: Int) {
		if (count == 0) badgeCounts.remove(itemId) else badgeCounts[itemId] = count
		rebuildComposeItems()
	}

	fun setComposeItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		if (isVisible) hiddenIds.remove(itemId) else hiddenIds.add(itemId)
		rebuildComposeItems()
	}

	private fun rebuildComposeItems() {
		val out = ArrayList<FloatingNavBarItem>(sourceItems.size.coerceAtMost(maxRenderedItems))
		for (item in sourceItems) {
			if (item.id in hiddenIds) continue
			out += FloatingNavBarItem(
				id = item.id,
				titleRes = item.title,
				icon = item.icon,
				badgeCount = badgeCounts[item.id] ?: 0,
			)
			if (out.size >= maxRenderedItems) break
		}
		composeItemsState.value = out
		selectedIdState.value = selectedItemId
	}

	private fun hideNativeChildren() {
		// composeView isn't in the tree yet when this is called from init, so every
		// existing child is from the NavigationBarView superclass and should be hidden.
		for (i in 0 until childCount) {
			val child = getChildAt(i)
			if (child !== composeView) {
				child.visibility = View.GONE
			}
		}
	}

	companion object {
		const val MAX_RENDERED_ITEMS = 5
	}
}
