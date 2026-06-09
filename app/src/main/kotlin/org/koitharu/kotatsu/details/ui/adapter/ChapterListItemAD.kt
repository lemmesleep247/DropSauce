package org.koitharu.kotatsu.details.ui.adapter

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeColorStateList
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemChapterBinding
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.list.ui.model.ListModel
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

fun chapterListItemAD(
	clickListener: OnListItemClickListener<ChapterListItem>,
	accentColorProvider: () -> Int? = { null },
) = adapterDelegateViewBinding<ChapterListItem, ListModel, ItemChapterBinding>(
	viewBinding = { inflater, parent -> ItemChapterBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is ChapterListItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)

	bind {
		itemView.setBackgroundResource(if (item.isCurrent) R.drawable.bg_current_chapter_item else R.drawable.list_selector)
		binding.textViewTitle.text = item.getTitle(context.resources)
		binding.textViewDescription.textAndVisible = item.description
		when {
			item.isCurrent -> {
				val accent = accentColorProvider()
				binding.textViewTitle.drawableStart = null
				if (accent != null) {
					// Recolor the current-chapter pill outline + text to the cover accent.
					val strokePx = context.resources.displayMetrics.density.roundToInt().coerceAtLeast(1)
					itemView.background?.mutate()?.let { bg ->
						itemView.background = bg
						bg.findStrokeShape()?.setStroke(strokePx, accent)
					}
					binding.textViewTitle.setTextColor(accent)
					binding.textViewDescription.setTextColor(accent)
				} else {
					binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
					binding.textViewDescription.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				}
				binding.textViewTitle.typeface = Typeface.DEFAULT_BOLD
				binding.textViewDescription.typeface = Typeface.DEFAULT_BOLD
			}

			item.isUnread -> {
				binding.textViewTitle.drawableStart = if (item.isNew) {
					ContextCompat.getDrawable(context, R.drawable.ic_new)
				} else {
					null
				}
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorPrimary))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(materialR.attr.colorOutline))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewDescription.typeface = Typeface.DEFAULT
			}

			else -> {
				binding.textViewTitle.drawableStart = null
				binding.textViewTitle.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewDescription.setTextColor(context.getThemeColorStateList(android.R.attr.textColorHint))
				binding.textViewTitle.typeface = Typeface.DEFAULT
				binding.textViewDescription.typeface = Typeface.DEFAULT
			}
		}
		binding.imageViewBookmarked.isVisible = item.isBookmarked
		binding.imageViewDownloaded.isVisible = item.isDownloaded
	}
}

// Finds the stroked shape inside the current-chapter background (ripple > inset > shape) so its
// outline color can be swapped to the cover accent at bind time.
private fun Drawable.findStrokeShape(): GradientDrawable? = when (this) {
	is GradientDrawable -> this
	is InsetDrawable -> drawable?.findStrokeShape()
	is LayerDrawable -> {
		var result: GradientDrawable? = null
		for (i in 0 until numberOfLayers) {
			result = getDrawable(i).findStrokeShape()
			if (result != null) break
		}
		result
	}

	else -> null
}
