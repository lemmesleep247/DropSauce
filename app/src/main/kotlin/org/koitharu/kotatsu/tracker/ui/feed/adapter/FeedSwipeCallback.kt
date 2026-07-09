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
import kotlin.math.tanh
import com.google.android.material.R as materialR

/**
 * Swipe right → delete a single feed entry, swipe left → mark it as read.
 * The reveal fills the row's full height and width with a rounded (stadium) pill + centered icon and
 * fires a haptic tick when the action threshold is crossed. An already-read entry can't be marked
 * read: its swipe rubber-bands to a shallow point and greys out, and the action never fires.
 */
class FeedSwipeCallback(
	context: Context,
	private val onAction: (item: FeedItem, isRead: Boolean, position: Int) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

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
		// A leftward swipe (mark read) on an already-read entry must never commit.
		if (lastDx < 0f && viewHolder.getItem(FeedItem::class.java)?.isNew == false) {
			return 3f
		}
		return 0.4f
	}

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		val item = viewHolder.getItem(FeedItem::class.java) ?: return
		onAction(item, direction == ItemTouchHelper.LEFT, viewHolder.bindingAdapterPosition)
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
			val isRead = dX < 0f
			val isAlreadyRead = isRead && viewHolder.getItem(FeedItem::class.java)?.isNew == false
			if (isAlreadyRead && isCurrentlyActive) {
				// smooth rubber-band toward a shallow limit while actively dragging (tanh is monotonic
				// with f(0)=0). Only while the finger is moving the view: on release, ItemTouchHelper's
				// own settle animation interpolates dX from the already-clamped translation back to 0,
				// so re-applying tanh here would clamp an already-clamped value and jump on release.
				val limit = view.width * 0.22f
				effectiveDx = -(limit * tanh(abs(dX) / limit))
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
				// swipe left reveals the right side
				left = view.right + effectiveDx
				right = view.right.toFloat()
			} else {
				// swipe right reveals the left side
				left = view.left.toFloat()
				right = view.left + effectiveDx
			}
			if (right - left > 0f) {
				// full-height stadium pill filling the revealed region
				val radius = (bottom - top) / 2f
				c.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)

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
