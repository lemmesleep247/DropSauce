package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableEnd
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemFeedBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import com.google.android.material.R as materialR

fun feedItemAD(
	clickListener: OnListItemClickListener<FeedItem>,
) = adapterDelegateViewBinding<FeedItem, ListModel, ItemFeedBinding>(
	{ inflater, parent -> ItemFeedBinding.inflate(inflater, parent, false) },
) {
	val indicatorNew = ContextCompat.getDrawable(context, R.drawable.ic_new)
	val density = context.resources.displayMetrics.density
	val cornerLarge = 24f * density
	val cornerSmall = 4f * density
	val gapSeam = (3f * density).toInt()
	val gapGroup = (12f * density).toInt()
	val containerColor = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.DKGRAY)
	val rippleColor = context.getThemeColor(android.R.attr.colorControlHighlight, Color.GRAY)

	fun backgroundFor(position: FeedItem.GroupPosition): RippleDrawable {
		val shape = ShapeAppearanceModel.builder().apply {
			when (position) {
				FeedItem.GroupPosition.SINGLE -> setAllCornerSizes(cornerLarge)
				FeedItem.GroupPosition.MIDDLE -> setAllCornerSizes(cornerSmall)
				FeedItem.GroupPosition.FIRST -> {
					setTopLeftCornerSize(cornerLarge)
					setTopRightCornerSize(cornerLarge)
					setBottomLeftCornerSize(cornerSmall)
					setBottomRightCornerSize(cornerSmall)
				}

				FeedItem.GroupPosition.LAST -> {
					setTopLeftCornerSize(cornerSmall)
					setTopRightCornerSize(cornerSmall)
					setBottomLeftCornerSize(cornerLarge)
					setBottomRightCornerSize(cornerLarge)
				}
			}
		}.build()
		val fill = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(containerColor) }
		val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) }
		return RippleDrawable(ColorStateList.valueOf(rippleColor), fill, mask)
	}

	itemView.setOnClickListener {
		clickListener.onItemClick(item, it)
	}

	bind {
		itemView.background = backgroundFor(item.groupPosition)
		itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			bottomMargin = when (item.groupPosition) {
				FeedItem.GroupPosition.FIRST, FeedItem.GroupPosition.MIDDLE -> gapSeam
				else -> gapGroup
			}
		}
		binding.imageViewCover.setImageAsync(item.imageUrl, item.manga.source)
		binding.textViewTitle.text = item.title
		val chapters = item.chapters
		binding.textViewSummary.isVisible = chapters.isEmpty()
		binding.textViewTitle.drawableEnd = if (item.isNew && chapters.isNotEmpty()) indicatorNew else null
		if (chapters.isEmpty()) {
			binding.textViewSummary.text = context.resources.getQuantityStringSafe(
				R.plurals.new_chapters,
				item.count,
				item.count,
			)
			binding.textViewSummary.drawableStart = if (item.isNew) {
				indicatorNew
			} else {
				null
			}
		}
		binding.layoutChapters.removeAllViews()
		val inflater = LayoutInflater.from(context)
		for (chapter in chapters) {
			val textView = inflater.inflate(
				R.layout.item_feed_chapter,
				binding.layoutChapters,
				false,
			) as TextView
			textView.text = chapter.name
			binding.layoutChapters.addView(textView)
		}
	}
}
