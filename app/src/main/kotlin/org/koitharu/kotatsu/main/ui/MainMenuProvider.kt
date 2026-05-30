package org.koitharu.kotatsu.main.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter

/**
 * Main search-bar menu. The app-update indicator that used to live here was moved to the
 * Settings icon (red dot) and an in-settings banner, so this menu is currently empty but is
 * kept as the attachment point for any future main-screen actions.
 */
class MainMenuProvider(
	@Suppress("unused") private val router: AppRouter,
	@Suppress("unused") private val viewModel: MainViewModel,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_main, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
}
