package org.koitharu.kotatsu.list.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil3.size.Size
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import androidx.appcompat.R as appcompatR

fun mangaGridItemAD(
	sizeResolver: ItemSizeResolver,
	clickListener: OnListItemClickListener<MangaListModel>,
) = adapterDelegateViewBinding<MangaGridModel, ListModel, ItemMangaGridBinding>(
	{ inflater, parent -> ItemMangaGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	// The overlay title is the one that adapts to the grid size (white, on the scrim).
	sizeResolver.attachToView(itemView, binding.textViewTitleOverlay, binding.progressView)

	val density = context.resources.displayMetrics.density
	// Title scrim: a short, fairly dark fade of a dark shade of the theme accent, just tall enough
	// for two lines of title, with its bottom corners matching the cover's rounding.
	val darkAccent = ColorUtils.blendARGB(context.getThemeColor(appcompatR.attr.colorPrimary), Color.BLACK, 0.78f)
	binding.viewScrim.background = GradientDrawable(
		GradientDrawable.Orientation.BOTTOM_TOP,
		intArrayOf(
			ColorUtils.setAlphaComponent(darkAccent, 0xF2),
			ColorUtils.setAlphaComponent(darkAccent, 0xC0),
			ColorUtils.setAlphaComponent(darkAccent, 0x00),
		),
	).apply {
		val r = 16f * density
		cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
	}

	bind { payloads ->
		itemView.setTooltipCompat(item.getSummary(context))
		val isTitleOverCover = item.isTitleOverCover && !item.isTitleHidden
		binding.textViewTitleOverlay.text = item.title
		binding.textViewTitle.text = item.title
		binding.textViewTitleOverlay.isVisible = isTitleOverCover
		binding.viewScrim.isVisible = isTitleOverCover
		binding.textViewTitle.isVisible = !item.isTitleHidden && !isTitleOverCover
		// Let the overlay title span almost the full width, only reserving room for the progress
		// ring when one is actually shown.
		binding.textViewTitleOverlay.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginEnd = ((if (item.progress != null) 44f else 12f) * density).toInt()
		}
		binding.progressView.setProgress(item.progress, PAYLOAD_PROGRESS_CHANGED in payloads)
		with(binding.iconsView) {
			clearIcons()
			if (item.isSaved) addIcon(R.drawable.ic_storage)
			if (item.isFavorite) addIcon(R.drawable.ic_heart_outline)
			isVisible = iconsCount > 0
		}
		// Load at a stable size derived from the grid cell width (not the transient measured view
		// size), so covers in the ViewPager2-hosted grids stay sharp after rotation/settling.
		val coverWidth = sizeResolver.cellWidth
		binding.imageViewCover.exactImageSize = if (coverWidth > 0) {
			Size(coverWidth, coverWidth * 18 / 13)
		} else {
			null
		}
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0
	}
}
