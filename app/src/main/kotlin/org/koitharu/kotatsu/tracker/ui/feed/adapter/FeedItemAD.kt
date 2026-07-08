package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableEnd
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.databinding.ItemFeedBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem

fun feedItemAD(
	clickListener: OnListItemClickListener<FeedItem>,
	chapterClickListener: (FeedItem, TrackingLogItem.Chapter) -> Unit,
) = adapterDelegateViewBinding<FeedItem, ListModel, ItemFeedBinding>(
	{ inflater, parent -> ItemFeedBinding.inflate(inflater, parent, false) },
) {
	val indicatorNew = ContextCompat.getDrawable(context, R.drawable.ic_new)

	itemView.setOnClickListener {
		clickListener.onItemClick(item, it)
	}

	bind {
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
			textView.setOnClickListener {
				chapterClickListener(item, chapter)
			}
			binding.layoutChapters.addView(textView)
		}
	}
}
