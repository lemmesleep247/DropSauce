package org.koitharu.kotatsu.core.ui

import android.os.Bundle
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ActivityContainerBinding
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.main.ui.owners.SnackbarOwner

@AndroidEntryPoint
abstract class FragmentContainerActivity(private val fragmentClass: Class<out Fragment>) :
	BaseActivity<ActivityContainerBinding>(),
	AppBarOwner,
	SnackbarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val snackbarHost: CoordinatorLayout
		get() = viewBinding.root

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityContainerBinding.inflate(layoutInflater))
		viewBinding.statusBarScrim?.blurTarget = viewBinding.container
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val fm = supportFragmentManager
		if (fm.findFragmentById(R.id.container) == null) {
			fm.commit {
				setReorderingAllowed(true)
				replace(R.id.container, fragmentClass, getFragmentExtras())
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		// Top inset is not consumed and not padded here: the appbar's WindowInsetHolder occupies it
		// while expanded and scrolls away with the toolbar, letting content go edge-to-edge under
		// the status bar (protected by the StatusBarBlurView).
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
		)
		return insets
	}

	protected open fun getFragmentExtras(): Bundle? = intent.extras
}
