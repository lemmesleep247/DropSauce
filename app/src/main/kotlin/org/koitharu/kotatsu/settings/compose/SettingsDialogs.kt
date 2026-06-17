package org.koitharu.kotatsu.settings.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

/**
 * Material 3 single-choice picker dialog (replaces ListPreference's built-in dialog).
 */
@Composable
fun ChoiceDialog(
	title: String,
	entries: List<String>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
	onDismiss: () -> Unit,
) {
	val haptic = rememberHapticEffect()
	val select: (Int) -> Unit = { index ->
		haptic(HapticEffect.CONFIRM)
		onSelect(index)
		onDismiss()
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				entries.forEachIndexed { index, entry ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(min = 48.dp)
							.clip(RoundedCornerShape(12.dp))
							.clickable { select(index) }
							.padding(horizontal = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(
							selected = index == selectedIndex,
							onClick = { select(index) },
						)
						Spacer(Modifier.size(8.dp))
						Text(
							text = entry,
							style = MaterialTheme.typography.bodyLarge,
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) { Text("Cancel") }
		},
	)
}

/**
 * Material 3 multi-choice picker dialog (replaces MultiSelectListPreference's dialog).
 */
@Composable
fun MultiChoiceDialog(
	title: String,
	entries: List<String>,
	selectedIndices: Set<Int>,
	onConfirm: (Set<Int>) -> Unit,
	onDismiss: () -> Unit,
) {
	var current by remember { mutableStateOf(selectedIndices) }
	val haptic = rememberHapticEffect()
	val toggle: (Int, Boolean) -> Unit = { index, checked ->
		haptic(if (checked) HapticEffect.TOGGLE_OFF else HapticEffect.TOGGLE_ON)
		current = if (checked) current - index else current + index
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				entries.forEachIndexed { index, entry ->
					val checked = index in current
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(min = 48.dp)
							.clip(RoundedCornerShape(12.dp))
							.clickable { toggle(index, checked) }
							.padding(horizontal = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Checkbox(
							checked = checked,
							onCheckedChange = { toggle(index, checked) },
						)
						Spacer(Modifier.size(8.dp))
						Text(
							text = entry,
							style = MaterialTheme.typography.bodyLarge,
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}
		},
		confirmButton = {
			TextButton(onClick = {
				haptic(HapticEffect.CONFIRM)
				onConfirm(current)
				onDismiss()
			}) { Text("OK") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("Cancel") }
		},
	)
}

/**
 * Text input dialog (replaces EditTextPreference's dialog).
 */
@Composable
fun TextInputDialog(
	title: String,
	initialValue: String,
	hint: String? = null,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var value by remember { mutableStateOf(initialValue) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			OutlinedTextField(
				value = value,
				onValueChange = { value = it },
				placeholder = if (hint != null) {
					{ Text(hint) }
				} else null,
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
			)
		},
		confirmButton = {
			TextButton(onClick = {
				onConfirm(value)
				onDismiss()
			}) { Text("OK") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("Cancel") }
		},
	)
}

/**
 * Integer-valued slider dialog used by settings screens.
 */
@Composable
fun SliderDialog(
	title: String,
	initialValue: Int,
	valueFrom: Int,
	valueTo: Int,
	stepSize: Int,
	unitSuffix: String = "",
	onConfirm: (Int) -> Unit,
	onDismiss: () -> Unit,
) {
	var value by remember { mutableFloatStateOf(initialValue.toFloat()) }
	val steps = if (stepSize > 0) ((valueTo - valueFrom) / stepSize - 1).coerceAtLeast(0) else 0
	val haptic = rememberHapticEffect()
	var lastStep by remember { mutableFloatStateOf(initialValue.toFloat()) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column {
				Text(
					text = "${value.roundToInt()}$unitSuffix",
					style = MaterialTheme.typography.headlineSmall,
					modifier = Modifier
						.fillMaxWidth()
						.padding(bottom = 12.dp),
				)
				Slider(
					value = value,
					onValueChange = {
						value = it
						val snapped = it.roundToInt().toFloat()
						if (snapped != lastStep) {
							lastStep = snapped
							haptic(HapticEffect.TICK)
						}
					},
					onValueChangeFinished = { haptic(HapticEffect.GESTURE_END) },
					valueRange = valueFrom.toFloat()..valueTo.toFloat(),
					steps = steps,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = {
				haptic(HapticEffect.CONFIRM)
				onConfirm(value.roundToInt())
				onDismiss()
			}) { Text("OK") }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("Cancel") }
		},
	)
}

/**
 * Generic info / confirm dialog with a single OK button. Useful for "are you sure"-style
 * prompts launched from a Preference's onClick.
 */
@Composable
fun ConfirmDialog(
	title: String,
	message: String,
	confirmLabel: String = "OK",
	dismissLabel: String? = "Cancel",
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
		confirmButton = {
			TextButton(onClick = {
				onConfirm()
				onDismiss()
			}) { Text(confirmLabel) }
		},
		dismissButton = if (dismissLabel != null) {
			{ TextButton(onClick = onDismiss) { Text(dismissLabel) } }
		} else null,
	)
}

/** Small visual chip used as a value summary in some settings rows. */
@Composable
fun ValueChip(text: String) {
	Box(
		modifier = Modifier
			.heightIn(min = 24.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.primaryContainer)
			.padding(horizontal = 10.dp, vertical = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
