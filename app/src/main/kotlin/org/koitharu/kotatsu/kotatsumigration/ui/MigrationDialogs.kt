package org.koitharu.kotatsu.kotatsumigration.ui

import android.content.Context
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.kotatsumigration.domain.MigrationSummary

/**
 * Shown after a Kotatsu→Mihon migration finishes (auto-run after restore, or the manual action).
 * Tells the user how many entries were converted and, when some need extensions, points them at
 * the "Recommended for your library" section of the extension manager.
 */
fun Context.showKotatsuMigrationCompleteDialog(summary: MigrationSummary) {
	if (summary.converted <= 0) {
		return
	}
	buildAlertDialog(this) {
		setTitle(R.string.kotatsu_migration_complete)
		setMessage(
			if (summary.missingExtensions.isEmpty()) {
				getString(R.string.kotatsu_migration_done_all, summary.converted)
			} else {
				getString(
					R.string.kotatsu_migration_done_pending,
					summary.converted,
					summary.total,
					summary.missingExtensions.joinToString("\n• ", prefix = "• "),
				)
			},
		)
		setPositiveButton(android.R.string.ok, null)
	}.show()
}

/**
 * Shown after restoring a Tachiyomi/Mihon backup that references extensions which aren't installed.
 * Lists them and points the user at the extension manager (where they also appear as recommended).
 */
fun Context.showExtensionInstallPromptDialog(missingSources: List<String>) {
	if (missingSources.isEmpty()) {
		return
	}
	buildAlertDialog(this) {
		setTitle(R.string.extensions_to_install_title)
		setMessage(
			getString(
				R.string.extensions_to_install_message,
				missingSources.joinToString("\n• ", prefix = "• "),
			),
		)
		setPositiveButton(android.R.string.ok, null)
	}.show()
}
