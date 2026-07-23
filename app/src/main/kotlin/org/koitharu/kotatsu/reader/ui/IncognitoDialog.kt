package org.koitharu.kotatsu.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

/**
 * M3 Expressive replacement for the reader's "incognito mode" prompt. Choose to read in incognito,
 * disable it, or cancel; an optional "don't ask again" persists the choice. Cancelling (button, back
 * or outside tap) runs [onCancel] — the reader closes, matching the old dialog.
 */
fun showIncognitoModeDialog(
	activity: FragmentActivity,
	onResult: (incognito: Boolean, dontAskAgain: Boolean) -> Unit,
	onCancel: () -> Unit,
) {
	showComposeDialog(activity, cancelable = true, onCancel = onCancel) { dismiss ->
		IncognitoContent(
			onResult = { incognito, dontAskAgain ->
				dismiss()
				onResult(incognito, dontAskAgain)
			},
			onCancel = {
				dismiss()
				onCancel()
			},
		)
	}
}

@Composable
private fun IncognitoContent(
	onResult: (incognito: Boolean, dontAskAgain: Boolean) -> Unit,
	onCancel: () -> Unit,
) {
	var dontAskAgain by remember { mutableStateOf(false) }
	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_incognito),
		title = stringResource(R.string.incognito_mode),
		message = stringResource(R.string.incognito_mode_hint_nsfw),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(12.dp))
				.clickable { dontAskAgain = !dontAskAgain }
				.padding(vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
			Spacer(Modifier.size(8.dp))
			Text(
				text = stringResource(R.string.dont_ask_again),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Spacer(Modifier.height(16.dp))
		ExpressivePillButton(
			text = stringResource(R.string.incognito),
			icon = painterResource(R.drawable.ic_incognito),
			primary = true,
			onClick = { onResult(true, dontAskAgain) },
		)
		Spacer(Modifier.height(8.dp))
		ExpressivePillButton(
			text = stringResource(R.string.disable),
			primary = false,
			onClick = { onResult(false, dontAskAgain) },
		)
		Spacer(Modifier.height(8.dp))
		ExpressiveDialogTextButton(
			text = stringResource(android.R.string.cancel),
			onClick = onCancel,
		)
	}
}
