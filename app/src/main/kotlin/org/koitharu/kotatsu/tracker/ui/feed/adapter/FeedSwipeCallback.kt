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
 * The reveal fills the row's full height and width with a rounded background + centered icon and
 * fires a haptic tick when the action threshold is crossed. An already-read entry can't be marked
 * read: its right-swipe is capped and greyed out, and the action never fires.
 */
class FeedSwipeCallback(
	context: Context,
	private val onAction: (item: FeedItem, isRead: Boolean, position: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

	private val density = context.resources.displayMetrics.density
	private val corner = 24f * density
	private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()
	private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_check)?.mutate()
	private val deleteBg = context.getThemeColor(materialR.attr.colorErrorContainer, Color.RED)
	private val readBg = context.getThemeColor(materialR.attr.colorPrimaryContainer, Color.BLUE)
	private val deleteIconTint = context.getThemeColor(materialR.attr.colorOnErrorContainer, Color.WHITE)
	private val readIconTint = context.getThemeColor(materialR.attr.colorOnPrimaryContainer, Color.WHITE)
	private val disabledBg = context.getThemeColor(materialR.attr.colorSurfaceVariant, Color.GRAY)
	private val disabledIconTint = context.getThemeColor(materialR.attr.colorOutline, Color.LTGRAY)

	private var hapticFired = false
	private var lastDx = 0f

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

	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
		// A rightward swipe (mark read) on an already-read entry must never commit.
		if (lastDx > 0f && viewHolder.getItem(FeedItem::class.java)?.isNew == false) {
			return 3f
		}
		return 0.4f
	}

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
		var effectiveDx = dX
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
			lastDx = dX
			val isRead = dX > 0f
			val isAlreadyRead = isRead && viewHolder.getItem(FeedItem::class.java)?.isNew == false
			if (isAlreadyRead) {
				// pin the swipe at a shallow point so it reads as "not available"
				effectiveDx = dX.coerceAtMost(view.width * 0.2f)
			}
			bgPaint.color = when {
				isAlreadyRead -> disabledBg
				isRead -> readBg
				else -> deleteBg
			}
			val top = view.top.toFloat()
			val bottom = view.bottom.toFloat()
			val left: Float
			val right: Float
			if (isRead) {
				left = view.left.toFloat()
				right = view.left + effectiveDx
			} else {
				left = view.right + effectiveDx
				right = view.right.toFloat()
			}
			if (right - left > 0f) {
				// rounded rect spanning the full row bounds, clipped to the revealed region so it
				// fills the row's height and touches its sides with rounded outer corners
				c.save()
				c.clipRect(left, top, right, bottom)
				c.drawRoundRect(view.left.toFloat(), top, view.right.toFloat(), bottom, corner, corner, bgPaint)
				c.restore()

				val icon = if (isRead) readIcon else deleteIcon
				if (icon != null && right - left >= icon.intrinsicWidth) {
					val progress = (abs(effectiveDx) / view.width).coerceIn(0f, 1f)
					icon.alpha = ((progress * 3f).coerceAtMost(1f) * 255).toInt()
					icon.setTint(
						when {
							isAlreadyRead -> disabledIconTint
							isRead -> readIconTint
							else -> deleteIconTint
						},
					)
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

			if (!isAlreadyRead) {
				val passedThreshold = abs(dX) >= 0.4f * view.width
				if (isCurrentlyActive) {
					if (passedThreshold && !hapticFired) {
						view.hapticFeedback(HapticEffect.CONFIRM)
						hapticFired = true
					} else if (!passedThreshold) {
						hapticFired = false
					}
				}
			}
		}
		super.onChildDraw(c, recyclerView, viewHolder, effectiveDx, dY, actionState, isCurrentlyActive)
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		super.clearView(recyclerView, viewHolder)
		hapticFired = false
		lastDx = 0f
	}
}
