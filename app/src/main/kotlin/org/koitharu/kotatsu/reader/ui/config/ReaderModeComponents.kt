package org.koitharu.kotatsu.reader.ui.config

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ReaderMode

@Composable
internal fun ReadModeSection(
	selectedMode: ReaderMode,
	onModeSelected: (ReaderMode) -> Unit,
) {
	val modes = listOf(
		ReaderMode.STANDARD to (R.string.standard to R.drawable.ic_reader_ltr),
		ReaderMode.REVERSED to (R.string.r_to_l to R.drawable.ic_reader_rtl),
		ReaderMode.VERTICAL to (R.string.vertical to R.drawable.ic_reader_vertical),
		ReaderMode.WEBTOON to (R.string.webtoon to R.drawable.ic_reader_webtoon),
	)
	val selectedIndex = modes.indexOfFirst { it.first == selectedMode }.coerceAtLeast(0)

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Surface(
			shape = RoundedCornerShape(24.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			modifier = Modifier.fillMaxWidth(),
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(112.dp)
					.padding(8.dp),
			) {
				val targetBias = when (selectedIndex) {
					0 -> -1f
					1 -> -1f / 3f
					2 -> 1f / 3f
					3 -> 1f
					else -> -1f
				}
				val animatedBias by animateFloatAsState(
					targetValue = targetBias,
					animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
					label = "mode_highlighter",
				)

				Box(
					modifier = Modifier
						.fillMaxHeight()
						.fillMaxWidth(0.25f)
						.align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
						.clip(RoundedCornerShape(22.dp))
						.background(MaterialTheme.colorScheme.primary),
				)

				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
				) {
					modes.forEach { (mode, pairRes) ->
						val (labelRes, iconRes) = pairRes
						val isSelected = selectedMode == mode
						val contentColor by animateFloatAsState(
							targetValue = if (isSelected) 1f else 0.6f,
							animationSpec = tween(durationMillis = 300),
							label = "mode_content_alpha",
						)

						Box(
							modifier = Modifier
								.weight(1f)
								.fillMaxHeight()
								.clickable { onModeSelected(mode) },
							contentAlignment = Alignment.Center,
						) {
							Row(
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.Center,
							) {
								Icon(
									painter = painterResource(iconRes),
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSurface.copy(alpha = contentColor),
									modifier = Modifier.size(18.dp),
								)
								Spacer(modifier = Modifier.width(6.dp))
								Text(
									text = stringResource(labelRes),
									style = MaterialTheme.typography.titleSmall,
									fontWeight = FontWeight.Bold,
									color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentColor),
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
internal fun DoublePageConfigSection(
	isModeStandardOrReversed: Boolean,
	isDoubleOnLandscape: Boolean,
	onDoubleOnLandscapeChange: (Boolean) -> Unit,
	isDoubleOnFoldable: Boolean,
	onDoubleOnFoldableChange: (Boolean) -> Unit,
	sensitivity: Float,
	onSensitivityChange: (Float) -> Unit,
) {
	val sectionAlpha = if (isModeStandardOrReversed) 1f else 0.38f

	Surface(
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
		shadowElevation = 1.dp,
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.alpha(sectionAlpha)
				.padding(vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(enabled = isModeStandardOrReversed) { onDoubleOnLandscapeChange(!isDoubleOnLandscape) }
					.padding(horizontal = 20.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_split_horizontal),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(22.dp),
				)
				Spacer(modifier = Modifier.width(16.dp))
				Text(
					text = stringResource(R.string.use_two_pages_landscape),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f),
				)
				Switch(
					checked = isDoubleOnLandscape,
					onCheckedChange = onDoubleOnLandscapeChange,
					enabled = isModeStandardOrReversed,
				)
			}

			val subOptionsAlpha = if (isModeStandardOrReversed && isDoubleOnLandscape) 1f else 0.38f

			Column(
				modifier = Modifier
					.fillMaxWidth()
					.alpha(subOptionsAlpha),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clickable(enabled = isModeStandardOrReversed && isDoubleOnLandscape) { onDoubleOnFoldableChange(!isDoubleOnFoldable) }
						.padding(horizontal = 20.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Spacer(modifier = Modifier.width(38.dp))
					Text(
						text = stringResource(R.string.auto_double_foldable),
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.weight(1f),
					)
					Switch(
						checked = isDoubleOnFoldable,
						onCheckedChange = onDoubleOnFoldableChange,
						enabled = isModeStandardOrReversed && isDoubleOnLandscape,
					)
				}

				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 20.dp, vertical = 8.dp),
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = stringResource(R.string.two_page_scroll_sensitivity),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onSurface,
						)
						Text(
							text = "${sensitivity.toInt()}%",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Slider(
						value = sensitivity,
						onValueChange = onSensitivityChange,
						valueRange = 50f..200f,
						enabled = isModeStandardOrReversed && isDoubleOnLandscape,
					)
				}
			}
		}
	}
}
