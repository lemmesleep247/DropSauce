package org.koitharu.kotatsu.widget.stats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.koitharu.kotatsu.R
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

object StatsChartRenderer {

	fun render(
		context: Context,
		daily: LongArray,
		widthPx: Int,
		heightPx: Int,
	): Bitmap {
		val w = widthPx.coerceAtLeast(64)
		val h = heightPx.coerceAtLeast(48)
		val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val barCount = daily.size.coerceAtLeast(1)
		val spacing = w * 0.06f / (barCount - 1).coerceAtLeast(1)
		val totalSpacing = spacing * (barCount - 1)
		val barWidth = (w - totalSpacing) / barCount
		val maxValue = (daily.maxOrNull() ?: 0L).coerceAtLeast(1L)
		val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = ContextCompat.getColor(context, R.color.kotatsu_primary)
		}
		val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = ContextCompat.getColor(context, R.color.widget_chart_label)
			textSize = h * 0.18f
			textAlign = Paint.Align.CENTER
			typeface = ResourcesCompat.getFont(context, R.font.gflex_body) ?: Typeface.DEFAULT
		}
		val labels = weekdayLabels(barCount)
		val labelHeight = labelPaint.textSize + h * 0.04f
		val chartTop = 0f
		val chartBottom = h - labelHeight
		val baselineHeight = (chartBottom - chartTop) * 0.06f
		val corner = (barWidth / 2f).coerceAtMost((chartBottom - chartTop) / 4f)
		for (i in 0 until barCount) {
			val x = i * (barWidth + spacing)
			val value = daily[i]
			if (value > 0) {
				val ratio = value.toFloat() / maxValue.toFloat()
				val barH = (chartBottom - baselineHeight) * ratio
				val top = chartBottom - baselineHeight - barH
				val rectActive = RectF(x, top, x + barWidth, chartBottom - baselineHeight)
				canvas.drawRoundRect(rectActive, corner, corner, activePaint)
			}
			val label = labels.getOrNull(i).orEmpty()
			if (label.isNotEmpty()) {
				val cx = x + barWidth / 2f
				val cy = chartBottom + labelHeight - h * 0.04f
				canvas.drawText(label, cx, cy, labelPaint)
			}
		}
		return bitmap
	}

	/**
	 * Single-letter weekday labels starting with Monday (matches `daily[0]` = Mon … `daily[6]` = Sun).
	 * Uses `DayOfWeek.getDisplayName(NARROW)` so Mon→"M", Tue→"T", etc., localised correctly.
	 */
	private val mondayFirstWeek = listOf(
		DayOfWeek.MONDAY,
		DayOfWeek.TUESDAY,
		DayOfWeek.WEDNESDAY,
		DayOfWeek.THURSDAY,
		DayOfWeek.FRIDAY,
		DayOfWeek.SATURDAY,
		DayOfWeek.SUNDAY,
	)

	private fun weekdayLabels(count: Int): List<String> {
		val locale = Locale.getDefault()
		return (0 until count).map { i ->
			mondayFirstWeek[i % mondayFirstWeek.size].getDisplayName(TextStyle.NARROW, locale)
		}
	}
}
