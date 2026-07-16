package org.koitharu.kotatsu.reader.ui.epub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.LineBackgroundSpan
import android.text.style.UpdateAppearance
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView

internal class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)

internal class HighlightColorSpan(var color: Int) : CharacterStyle(), UpdateAppearance {
	override fun updateDrawState(tp: android.text.TextPaint) {
		tp.bgColor = color
	}
}

internal class EpubSelectableTextView(
	context: Context,
	private val highlightColor: Int,
) : AppCompatTextView(context) {
	private val selectionBackgroundColor = highlightColor
	private var selectionBackgroundSpan: SelectionBackgroundSpan? = null
	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val handleRadius = 9f * resources.displayMetrics.density
	private val handleStemWidth = 2f * resources.displayMetrics.density

	init {
		handlePaint.color = ColorUtils.setAlphaComponent(selectionBackgroundColor, 255)
		setHighlightColor(android.graphics.Color.TRANSPARENT)
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			textSelectHandleLeft?.mutate()?.apply { alpha = 0 }?.let(::setTextSelectHandleLeft)
			textSelectHandleRight?.mutate()?.apply { alpha = 0 }?.let(::setTextSelectHandleRight)
			textSelectHandle?.mutate()?.apply { alpha = 0 }?.let(::setTextSelectHandle)
		}
	}

	override fun onSelectionChanged(selStart: Int, selEnd: Int) {
		super.onSelectionChanged(selStart, selEnd)
		val spannable = text as? Spannable ?: return
		selectionBackgroundSpan?.let(spannable::removeSpan)
		selectionBackgroundSpan = null
		if (selStart < 0 || selEnd < 0 || selStart == selEnd) return
		val span = SelectionBackgroundSpan(this, selectionBackgroundColor, selStart, selEnd)
		selectionBackgroundSpan = span
		spannable.setSpan(
			span,
			minOf(selStart, selEnd),
			maxOf(selStart, selEnd),
			Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !hasSelection() || !isFocused) return
		val start = minOf(selectionStart, selectionEnd)
		val end = maxOf(selectionStart, selectionEnd)
		drawHandle(canvas, start)
		drawHandle(canvas, end)
	}

	private fun drawHandle(canvas: Canvas, offset: Int) {
		val textLayout = layout ?: return
		val line = textLayout.getLineForOffset(offset)
		val x = compoundPaddingLeft - scrollX + correctedHorizontal(offset, line)
		val metrics = paint.fontMetrics
		val y = extendedPaddingTop - scrollY + textLayout.getLineBaseline(line) + metrics.descent
		canvas.drawRect(x - handleStemWidth / 2f, y, x + handleStemWidth / 2f, y + handleRadius, handlePaint)
		canvas.drawCircle(x, y + handleRadius, handleRadius, handlePaint)
	}

	fun correctedHorizontal(offset: Int, line: Int): Float {
		val textLayout = layout ?: return 0f
		val horizontal = textLayout.getPrimaryHorizontal(offset)
		if (Build.VERSION.SDK_INT < 26 || justificationMode != Layout.JUSTIFICATION_MODE_INTER_WORD) return horizontal
		val lineStart = textLayout.getLineStart(line)
		val lineEnd = textLayout.getLineEnd(line)
		if (lineEnd >= text.length || text[lineEnd - 1] == '\n') return horizontal
		var visibleEnd = lineEnd
		while (visibleEnd > lineStart && text[visibleEnd - 1].isWhitespace()) visibleEnd--
		val spaces = (lineStart until visibleEnd).count { text[it] == ' ' }
		if (spaces == 0) return horizontal
		val extraPerSpace = (textLayout.width - paint.measureText(text, lineStart, visibleEnd)) / spaces
		val spacesBefore = (lineStart until offset.coerceAtMost(visibleEnd)).count { text[it] == ' ' }
		return horizontal + textLayout.getParagraphDirection(line) * extraPerSpace * spacesBefore
	}

	fun selectionEndHorizontal(offset: Int, line: Int): Float {
		val textLayout = layout ?: return 0f
		if (offset < textLayout.getLineEnd(line)) return correctedHorizontal(offset, line)
		return if (textLayout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
			textLayout.getLineLeft(line)
		} else {
			textLayout.getLineRight(line)
		}
	}
}

internal class SelectionBackgroundSpan(
	private val textView: EpubSelectableTextView,
	color: Int,
	start: Int,
	end: Int,
) : LineBackgroundSpan, NoCopySpan {
	private val selectionStart = minOf(start, end)
	private val selectionEnd = maxOf(start, end)
	private val backgroundPaint = Paint().apply { this.color = color }

	override fun drawBackground(
		canvas: Canvas,
		paint: Paint,
		left: Int,
		right: Int,
		top: Int,
		baseline: Int,
		bottom: Int,
		text: CharSequence,
		start: Int,
		end: Int,
		lineNumber: Int,
	) {
		val rangeStart = maxOf(start, selectionStart)
		val rangeEnd = minOf(end, selectionEnd)
		if (rangeStart >= rangeEnd) return
		val x1 = textView.correctedHorizontal(rangeStart, lineNumber)
		val x2 = textView.selectionEndHorizontal(rangeEnd, lineNumber)
		val metrics = paint.fontMetrics
		canvas.drawRect(
			minOf(x1, x2),
			baseline + metrics.ascent,
			maxOf(x1, x2),
			baseline + metrics.descent,
			backgroundPaint,
		)
	}
}

internal class HighlightMarker(val bookmarkId: Long) : CharacterStyle() {
	override fun updateDrawState(tp: android.text.TextPaint) = Unit
}
