package org.koitharu.kotatsu.settings.compose

/**
 * Marker interface for settings fragments whose content is rendered with Jetpack Compose
 * and which manage their own top app bar via [SettingsScaffold]. SettingsActivity uses this
 * to decide whether to show its legacy MaterialToolbar or hide it and let Compose own the
 * top edge of the screen.
 */
interface ComposeOwnedScreen
