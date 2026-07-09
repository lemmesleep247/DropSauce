package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import kotlin.math.abs
import com.google.android.material.R as materialR

/**
 * Swipe left → delete a single feed entry, swipe right → mark it as read.
 * Draws a Material 3 rounded background + icon under the row while swiping and fires a
 * haptic tick when the action threshold is crossed.
 */
class FeedSwipeCallback(
	context: Context,
	private val onAction: (item: FeedItem, isRead: Boolean, position: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

	private val density = context.resources.displayMetrics.density
	private val pad = 8f * density
	private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()
	private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_check)?.mutate()
	private val deleteBg = context.getThemeColor(materialR.attr.colorErrorContainer, Color.RED)
	private val readBg = context.getThemeColor(materialR.attr.colorPrimaryContainer, Color.BLUE)
	private val deleteIconTint = context.getThemeColor(materialR.attr.colorOnErrorContainer, Color.WHITE)
	private val readIconTint = context.getThemeColor(materialR.attr.colorOnPrimaryContainer, Color.WHITE)

	private var hapticFired = false

	override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
		// only feed rows are swipeable, not the date headers
		if (viewHolder.itemViewType != ListItemType.FEED.ordinal) {
			return 0
		}
		return super.getMovementFlags(recyclerView, viewHolder)
	}

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	): Boolean = false

	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.4f

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		val item = viewHolder.getItem(FeedItem::class.java) ?: return
		onAction(item, direction == ItemTouchHelper.RIGHT, viewHolder.bindingAdapterPosition)
	}

	override fun onChildDraw(
		c: Canvas,
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		dX: Float,
		dY: Float,
		actionState: Int,
		isCurrentlyActive: Boolean,
	) {
		val view = viewHolder.itemView
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
			val isRead = dX > 0f
			bgPaint.color = if (isRead) readBg else deleteBg
			val top = view.top + pad
			val bottom = view.bottom - pad
			val left: Float
			val right: Float
			if (isRead) {
				left = view.left + pad
				right = view.left + dX - pad
			} else {
				left = view.right + dX + pad
				right = view.right - pad
			}
			if (right - left > 0f) {
				// fully-rounded (stadium) pill inset from the row, matching the segmented cards
				val radius = (bottom - top) / 2f
				c.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)

				val icon = if (isRead) readIcon else deleteIcon
				if (icon != null && right - left >= icon.intrinsicWidth) {
					val progress = (abs(dX) / view.width).coerceIn(0f, 1f)
					icon.alpha = ((progress * 3f).coerceAtMost(1f) * 255).toInt()
					icon.setTint(if (isRead) readIconTint else deleteIconTint)
					val cx = ((left + right) / 2f).toInt()
					val cy = ((top + bottom) / 2f).toInt()
					icon.setBounds(
						cx - icon.intrinsicWidth / 2,
						cy - icon.intrinsicHeight / 2,
						cx + icon.intrinsicWidth / 2,
						cy + icon.intrinsicHeight / 2,
					)
					icon.draw(c)
				}
			}

			val passedThreshold = abs(dX) >= getSwipeThreshold(viewHolder) * view.width
			if (isCurrentlyActive) {
				if (passedThreshold && !hapticFired) {
					view.hapticFeedback(HapticEffect.CONFIRM)
					hapticFired = true
				} else if (!passedThreshold) {
					hapticFired = false
				}
			}
		}
		super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		super.clearView(recyclerView, viewHolder)
		hapticFired = false
	}
}
