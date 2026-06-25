package org.koitharu.kotatsu.core.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewbinding.ViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.util.SystemUiController

abstract class BaseFullscreenActivity<B : ViewBinding> :
	BaseActivity<B>() {

	protected lateinit var systemUiController: SystemUiController

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		@Suppress("DEPRECATION")
		with(window) {
			systemUiController = SystemUiController(this)
			statusBarColor = Color.TRANSPARENT
			navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
				ContextCompat.getColor(this@BaseFullscreenActivity, R.color.dim)
			} else {
				Color.TRANSPARENT
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				attributes.layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			}
		}
		systemUiController.setSystemUiVisible(true)
		// The reader toggles system bars (status + nav) together via [systemUiController]; if the
		// user opted to hide the status bar globally, re-apply that on top so the nav bar still
		// shows in "normal" mode but the status bar stays gone.
		hideStatusBarIfNeeded()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) {
			hideStatusBarIfNeeded()
		}
	}

	private fun hideStatusBarIfNeeded() {
		if (entryPoint.settings.isStatusBarHidden) {
			val controller = WindowCompat.getInsetsController(window, window.decorView)
			controller.hide(WindowInsetsCompat.Type.statusBars())
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}
}
