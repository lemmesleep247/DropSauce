package org.koitharu.kotatsu.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonBackupManager.Options
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.backup.local.domain.BackupUtils
import org.koitharu.kotatsu.backup.local.ui.backup.BackupService
import org.koitharu.kotatsu.backup.local.ui.periodical.PeriodicalBackupSettingsFragment
import org.koitharu.kotatsu.backup.local.ui.restore.RestoreDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject

@AndroidEntryPoint
class BackupSettingsFragment : BaseComposeSettingsFragment(R.string.backup_restore) {

	@Inject
	lateinit var backupManager: MihonBackupManager

	private val restoreMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			runMihonRestoreJob(uri, options = Options())
		}
	}

	private val createLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(BackupUtils.MIME_TYPE),
	) { uri ->
		if (uri != null && BackupService.start(requireContext(), uri)) {
			Toast.makeText(requireContext(), R.string.creating_backup, Toast.LENGTH_SHORT).show()
		}
	}

	private val restoreLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			RestoreDialogFragment.show(parentFragmentManager, uri)
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				BackupScreen(
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onCreateBackup = {
						createLocalBackupLauncher.launch(
							BackupUtils.generateFileName(requireContext()),
						)
					},
					onRestoreLocal = {
						restoreLocalBackupLauncher.launch(
							arrayOf(BackupUtils.MIME_TYPE, "application/*", "*/*"),
						)
					},
					onOpenPeriodic = {
						(activity as? SettingsActivity)?.openFragment(
							PeriodicalBackupSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onRestoreFromTachiyomi = {
						restoreMihonBackupLauncher.launch(arrayOf("application/*", "*/*"))
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
	}

	private fun runMihonRestoreJob(uri: Uri, options: Options) {
		lifecycleScope.launch {
			var restoreReport: RestoreReport? = null
			val result = runCatching {
				restoreReport = backupManager.restoreBackup(uri, options)
			}
			val message = result.fold(
				onSuccess = { getString(R.string.data_restored_success) },
				onFailure = { it.getDisplayMessage(resources) },
			)
			Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
			if (result.isSuccess) {
				restoreReport?.let {
					showRestoreDiagnostics(it)
					showRestoreNotification(it)
				}
			}
		}
	}

	private fun showRestoreDiagnostics(report: RestoreReport) {
		val lines = buildList {
			add(getString(R.string.restore_report_restored_simple, report.restoredMangaCount))
			if (report.missingSources.isNotEmpty()) {
				add(
					getString(
						R.string.restore_report_missing_extensions_compact,
						report.missingSources.joinToString("\n"),
					),
				)
			}
		}
		if (lines.size <= 1) return
		buildAlertDialog(requireContext()) {
			setTitle(R.string.restore_diagnostics_title)
			setMessage(lines.joinToString(separator = "\n\n"))
			setPositiveButton(android.R.string.ok, null)
		}.show()
	}

	private fun showRestoreNotification(report: RestoreReport) {
		val ctx = requireContext().applicationContext
		val manager = NotificationManagerCompat.from(ctx)
		val channel = NotificationChannelCompat.Builder(
			RESTORE_CHANNEL_ID,
			NotificationManagerCompat.IMPORTANCE_DEFAULT,
		)
			.setName(getString(R.string.backup_restore))
			.setShowBadge(false)
			.build()
		manager.createNotificationChannel(channel)
		if (!ctx.checkNotificationPermission(RESTORE_CHANNEL_ID)) return

		val details = buildString {
			append(getString(R.string.restore_report_restored_simple, report.restoredMangaCount))
			if (report.missingSources.isNotEmpty()) {
				append('\n')
				append(report.missingSources.joinToString())
			}
		}
		val notification = NotificationCompat.Builder(ctx, RESTORE_CHANNEL_ID)
			.setSmallIcon(R.drawable.general_notification)
			.setContentTitle(getString(R.string.data_restored_success))
			.setContentText(details)
			.setStyle(NotificationCompat.BigTextStyle().bigText(details))
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setSilent(true)
			.build()
		manager.notify(RESTORE_NOTIFICATION_ID, notification)
	}

	companion object {
		private const val RESTORE_CHANNEL_ID = "backup_restore"
		private const val RESTORE_NOTIFICATION_ID = 7002
	}
}

@Composable
private fun BackupScreen(
	onBack: () -> Unit,
	onCreateBackup: () -> Unit,
	onRestoreLocal: () -> Unit,
	onOpenPeriodic: () -> Unit,
	onRestoreFromTachiyomi: () -> Unit,
) {
	SettingsScaffold(title = stringResource(R.string.backup_restore), onBack = onBack) {
		item {
			SettingsGroup(title = stringResource(R.string.backup_restore)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.create_backup),
						subtitle = stringResource(R.string.create_backup_summary),
						icon = R.drawable.ic_save,
						
						shape = pos.shape,
						onClick = onCreateBackup,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_backup),
						subtitle = stringResource(R.string.restore_summary),
						icon = R.drawable.ic_revert,
						
						shape = pos.shape,
						onClick = onRestoreLocal,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.periodic_backups),
						subtitle = stringResource(R.string.periodic_backups_summary),
						icon = R.drawable.ic_backup_restore,
						
						shape = pos.shape,
						onClick = onOpenPeriodic,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.import_from_other_apps)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_from_tachiyomi),
						subtitle = stringResource(R.string.restore_tachiyomi_summary),
						icon = R.drawable.ic_revert,
						
						shape = pos.shape,
						onClick = onRestoreFromTachiyomi,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
