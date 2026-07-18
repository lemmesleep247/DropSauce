package org.koitharu.kotatsu.settings.compose

import androidx.annotation.StringRes
import android.os.Bundle
import androidx.fragment.app.Fragment

/**
 * Common base for any Compose-hosted settings screen.
 *
 * - Pushes the screen's title up to the host activity's MaterialToolbar in `onResume`,
 *   synchronously — no SideEffect race. This guarantees the title displayed in the
 *   activity's toolbar matches the current fragment by the time the user sees the frame
 *   after a back-pop.
 */
abstract class BaseComposeSettingsFragment(
	@StringRes private val titleId: Int,
) : Fragment() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		updateTitle()
	}

	override fun onStart() {
		super.onStart()
		updateTitle()
	}

	override fun onResume() {
		super.onResume()
		updateTitle()
	}

	private fun updateTitle() {
		if (titleId != 0) {
			activity?.setTitle(titleId)
		}
	}
}
