package org.koitharu.kotatsu.settings.compose

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/**
 * Common base for any Compose-hosted settings screen.
 *
 * - Provides the [ComposeOwnedScreen] marker so the activity (and the rest of the app) can
 *   identify Compose-driven settings fragments.
 * - Pushes the screen's title up to the host activity's MaterialToolbar in `onResume`,
 *   synchronously — no SideEffect race. This guarantees the title displayed in the
 *   activity's toolbar matches the current fragment by the time the user sees the frame
 *   after a back-pop.
 */
abstract class BaseComposeSettingsFragment(
	@StringRes private val titleId: Int,
) : Fragment(), ComposeOwnedScreen {

	override fun onResume() {
		super.onResume()
		if (titleId != 0) {
			activity?.setTitle(titleId)
		}
	}
}
