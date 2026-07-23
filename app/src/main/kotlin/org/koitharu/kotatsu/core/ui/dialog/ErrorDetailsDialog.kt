package org.koitharu.kotatsu.core.ui.dialog

import android.os.Bundle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getCauseUrl
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.core.util.ext.isReportable
import org.koitharu.kotatsu.core.util.ext.report
import org.koitharu.kotatsu.core.util.ext.requireSerializable
import javax.inject.Inject

@AndroidEntryPoint
class ErrorDetailsDialog : ComposeAlertDialogFragment() {

	private lateinit var exception: Throwable

	@Inject
	lateinit var appUpdateRepository: AppUpdateRepository

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val args = requireArguments()
		exception = args.requireSerializable(AppRouter.KEY_ERROR)
	}

	@Composable
	override fun Content() {
		val url = remember { exception.getCauseUrl()?.takeIf { it.isHttpUrl() } }
		val isUpdate = remember { appUpdateRepository.isUpdateAvailable }
		val isReportable = remember { exception.isReportable() }
		val disclaimerRes = remember {
			when {
				exception is InteractiveActionRequiredException || exception is CloudFlareProtectedException ->
					R.string.error_disclaimer_captcha_required
				isUpdate -> R.string.error_disclaimer_app_outdated
				isReportable -> R.string.error_disclaimer_report
				else -> 0
			}
		}
		val hasPrimary = isUpdate || isReportable
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_error_large),
			title = stringResource(R.string.error_details),
			message = exception.message,
		) {
			if (disclaimerRes != 0) {
				Text(
					text = stringResource(disclaimerRes),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(16.dp))
			}
			if (isUpdate) {
				ExpressivePillButton(text = stringResource(R.string.update), primary = true) {
					router.openAppUpdate()
					dismiss()
				}
				Spacer(Modifier.height(8.dp))
			} else if (isReportable) {
				ExpressivePillButton(text = stringResource(R.string.report), primary = true) {
					exception.report(silent = true)
					dismiss()
				}
				Spacer(Modifier.height(8.dp))
			}
			if (url != null) {
				ExpressivePillButton(text = stringResource(R.string.open_in_browser), primary = false) {
					router.openBrowser(url = url, source = null, title = null)
				}
				Spacer(Modifier.height(8.dp))
			}
			ExpressivePillButton(
				text = stringResource(androidx.preference.R.string.copy),
				primary = !hasPrimary && url == null,
			) {
				context?.copyToClipboard(getString(R.string.error), exception.stackTraceToString())
				dismiss()
			}
			Spacer(Modifier.height(8.dp))
			ExpressiveDialogTextButton(text = stringResource(R.string.close)) { dismiss() }
		}
	}
}
