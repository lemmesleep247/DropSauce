package org.koitharu.kotatsu.list.ui.adapter

import android.annotation.SuppressLint
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * Makes a manga title act as a "read now" shortcut: taps are handled separately from
 * the item card, with the title underlined only while pressed as a touch affordance.
 * Long-press falls through to the item view so selection mode keeps working.
 */
@SuppressLint("ClickableViewAccessibility") // onTouch only tweaks the underline and returns false
internal fun TextView.attachTitleClickToRead(itemView: View, onClick: (View) -> Unit) {
	setOnClickListener(onClick)
	setOnLongClickListener { itemView.performLongClick() }
	setOnTouchListener { _, event ->
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL -> paintFlags = paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
		}
		false
	}
}
