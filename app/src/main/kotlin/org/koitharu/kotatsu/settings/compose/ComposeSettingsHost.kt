package org.koitharu.kotatsu.settings.compose

import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.SettingsActivity

/**
 * Mixin for any settings Fragment that hosts a Compose tree drawing its own top app bar.
 *
 * The host [SettingsActivity] normally shows a MaterialToolbar above its FragmentContainerView
 * (via CoordinatorLayout + ScrollingViewBehavior). For Compose screens, we want the Compose
 * `MediumTopAppBar` to own the full top of the screen — so we hide the activity's toolbar AND
 * detach the FragmentContainerView from the AppBarLayout's behavior. The original behavior is
 * restored when this fragment leaves the screen.
 *
 * Call from `onViewCreated`, `onStart`, `onResume`, and `onStop` (passing `false` on stop).
 */
class ComposeSettingsHostController {

	private var savedContainerBehavior: CoordinatorLayout.Behavior<*>? = null
	private var captured = false

	fun apply(fragment: Fragment, active: Boolean) {
		val act = fragment.activity as? SettingsActivity ?: return
		val appbar = act.findViewById<View>(R.id.appbar) ?: return
		val container = act.findViewById<View>(R.id.container) ?: return
		val containerLp = container.layoutParams as? CoordinatorLayout.LayoutParams ?: return
		if (active) {
			if (!captured) {
				savedContainerBehavior = containerLp.behavior
				captured = true
			}
			appbar.visibility = View.GONE
			containerLp.behavior = null
			containerLp.topMargin = 0
		} else {
			appbar.visibility = View.VISIBLE
			containerLp.behavior =
				savedContainerBehavior ?: AppBarLayout.ScrollingViewBehavior()
		}
		container.layoutParams = containerLp
		(container.parent as? View)?.requestLayout()
	}
}
