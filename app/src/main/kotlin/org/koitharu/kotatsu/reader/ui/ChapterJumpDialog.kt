package org.koitharu.kotatsu.reader.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

/**
 * M3 Expressive dialog shown when the user opens a chapter away from their saved progress:
 * peek into it without touching history, or move the reading progress there.
 */
fun showChapterJumpDialog(
	activity: FragmentActivity,
	onPeek: () -> Unit,
	onMoveProgress: () -> Unit,
) {
	val dialog = ComponentDialog(activity)
	dialog.setContentView(
		ComposeView(activity).apply {
			setContent {
				DropSauceTheme {
					ChapterJumpContent(
						onPeek = {
							dialog.dismiss()
							onPeek()
						},
						onMoveProgress = {
							dialog.dismiss()
							onMoveProgress()
						},
					)
				}
			}
		},
	)
	dialog.window?.run {
		setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	}
	// plain dialog, not a DialogFragment — dismiss with the activity to avoid a leaked window
	val observer = object : DefaultLifecycleObserver {
		override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
	}
	activity.lifecycle.addObserver(observer)
	dialog.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
	dialog.show()
}

@Composable
private fun ChapterJumpContent(
	onPeek: () -> Unit,
	onMoveProgress: () -> Unit,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			shape = RoundedCornerShape(28.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = 400.dp),
		) {
			Column(
				modifier = Modifier.padding(24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Box(
					modifier = Modifier
						.size(56.dp)
						.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_eye),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSecondaryContainer,
						modifier = Modifier.size(28.dp),
					)
				}
				Spacer(Modifier.height(16.dp))
				Text(
					text = stringResource(R.string.chapter_jump_title),
					style = MaterialTheme.typography.headlineSmall,
					color = MaterialTheme.colorScheme.onSurface,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(R.string.chapter_jump_message),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(24.dp))
				Button(
					onClick = onPeek,
					shape = RoundedCornerShape(28.dp),
					modifier = Modifier
						.fillMaxWidth()
						.height(56.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_eye),
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
					Spacer(Modifier.size(8.dp))
					Text(
						text = stringResource(R.string.chapter_jump_peek),
						style = MaterialTheme.typography.labelLarge,
					)
				}
				Spacer(Modifier.height(8.dp))
				FilledTonalButton(
					onClick = onMoveProgress,
					shape = RoundedCornerShape(28.dp),
					modifier = Modifier
						.fillMaxWidth()
						.height(56.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_eye_check),
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
					Spacer(Modifier.size(8.dp))
					Text(
						text = stringResource(R.string.chapter_jump_move),
						style = MaterialTheme.typography.labelLarge,
					)
				}
			}
		}
	}
}
