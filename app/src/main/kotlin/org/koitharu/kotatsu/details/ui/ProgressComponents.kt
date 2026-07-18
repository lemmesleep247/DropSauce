package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.list.domain.ReadingProgress
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun ProgressCard(historyInfo: HistoryInfo, isLoading: Boolean, accent: Color) {
	val ctx = LocalContext.current
	val res = ctx.resources
	val chaptersText = when {
		isLoading && historyInfo.totalChapters < 0 -> stringResource(R.string.loading_)
		historyInfo.currentChapter >= 0 -> withTime(
			stringResource(R.string.chapter_d_of_d, historyInfo.currentChapter + 1, historyInfo.totalChapters),
			historyInfo, res,
		)
		historyInfo.totalChapters == 0 -> stringResource(R.string.no_chapters)
		historyInfo.totalChapters == -1 -> stringResource(R.string.error_occurred)
		else -> withTime(
			pluralStringResource(R.plurals.chapters, historyInfo.totalChapters, historyInfo.totalChapters),
			historyInfo, res,
		)
	}
	val hasHistory = historyInfo.history != null
	val percent = historyInfo.percent.coerceIn(0f, 1f)
	val showProgress = hasHistory && percent > 0f
	val displayPercent = if (ReadingProgress.isCompleted(historyInfo.percent)) 100 else (percent * 100f).toInt()

	SectionCard {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_read),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(22.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = chaptersText,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (showProgress) {
				Text(
					text = stringResource(R.string.percent_string_pattern, displayPercent.toString()),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = accent,
				)
			}
		}
		if (showProgress) {
			Spacer(Modifier.height(14.dp))
			WavyProgressBar(
				progress = percent,
				color = accent,
				trackColor = accent.copy(alpha = 0.22f),
				modifier = Modifier
					.fillMaxWidth()
					.height(14.dp),
			)
		}
	}
}

@Composable
internal fun WavyProgressBar(progress: Float, color: Color, trackColor: Color, modifier: Modifier) {
	val transition = rememberInfiniteTransition(label = "wave")
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = (2f * PI).toFloat(),
		animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
		label = "phase",
	)
	val animatedProgress by animateFloatAsState(
		targetValue = progress.coerceIn(0f, 1f),
		animationSpec = tween(600),
		label = "progress",
	)
	Canvas(modifier = modifier) {
		val midY = size.height / 2f
		val stroke = 4.5.dp.toPx()
		val activeW = size.width * animatedProgress
		val edgeFlatten = when {
			animatedProgress <= 0.05f -> animatedProgress / 0.05f
			animatedProgress >= 0.95f -> (1f - animatedProgress) / 0.05f
			else -> 1f
		}
		val amplitude = (size.height / 2f - stroke / 2f) * 0.9f * edgeFlatten
		val waveLength = 40.dp.toPx()
		// The wave must land exactly on the track's centerline at the junction, so its
		// amplitude fades out over the last quarter wavelength only.
		val endTaper = waveLength / 4f
		if (animatedProgress < 1f) {
			drawLine(
				color = trackColor,
				start = Offset(activeW, midY),
				end = Offset(size.width, midY),
				strokeWidth = stroke,
				cap = StrokeCap.Round,
			)
		}
		if (activeW > 0f) {
			val path = Path().apply {
				moveTo(0f, midY + amplitude * sin(phase))
				var x = 0f
				while (x <= activeW) {
					val envelope = ((activeW - x) / endTaper).coerceIn(0f, 1f)
					lineTo(x, midY + amplitude * envelope * sin((x / waveLength) * 2f * PI.toFloat() + phase))
					x += 3f
				}
				lineTo(activeW, midY)
			}
			drawPath(
				path = path,
				color = color,
				style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
			)
		}
	}
}

private fun withTime(base: String, info: HistoryInfo, res: android.content.res.Resources): String {
	val time = info.estimatedTime?.formatShort(res) ?: return base
	return res.getString(R.string.chapters_time_pattern, base, time)
}
