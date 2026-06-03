package org.koitharu.kotatsu.settings.sources.catalog

import android.animation.ValueAnimator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.appcompat.widget.TooltipCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.ItemEmptyCardBinding
import org.koitharu.kotatsu.databinding.ItemSourceCatalogBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

interface ExtensionActionListener {
	fun onExtensionActionClick(item: SourceCatalogItem.Extension)
	fun onExtensionSettingsClick(item: SourceCatalogItem.Extension)
	fun onExtensionItemClick(item: SourceCatalogItem.Extension)
	fun onExtensionHideClick(item: SourceCatalogItem.Extension)
}


fun sourceCatalogItemExtensionAD(
	listener: ExtensionActionListener,
) = adapterDelegateViewBinding<SourceCatalogItem.Extension, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	// Hide button corner morph: fully round (circle) when visible, rounded-square when hidden.
	val density = context.resources.displayMetrics.density
	val hideCircleRadius = (22 * density).toInt()
	val hideSquareRadius = (14 * density).toInt()

	binding.imageViewAdd.setOnClickListener {
		listener.onExtensionActionClick(item)
	}
	binding.imageViewSettings.setOnClickListener {
		listener.onExtensionSettingsClick(item)
	}
	binding.imageViewHide.setOnClickListener {
		// Start the circle <-> rounded-square morph synchronously on tap. The list rebind that
		// follows (via the settings flow) is async and must NOT restart or skip this animation.
		val btn = binding.imageViewHide
		// item.isHidden is still the pre-toggle value here, so morph to the opposite shape.
		val target = if (item.isHidden) hideCircleRadius else hideSquareRadius
		(btn.getTag(R.id.tag_hide_corner_anim) as? ValueAnimator)?.cancel()
		val animator = ValueAnimator.ofInt(btn.cornerRadius, target).apply {
			duration = 300L
			interpolator = FastOutSlowInInterpolator()
			addUpdateListener { btn.cornerRadius = it.animatedValue as Int }
		}
		btn.setTag(R.id.tag_hide_corner_anim, animator)
		btn.setTag(R.id.tag_hide_state, item.packageName)
		animator.start()
		listener.onExtensionHideClick(item)
	}
	binding.root.setOnClickListener {
		listener.onExtensionItemClick(item)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	val compactEndPadding = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0)

	bind {
		val isInProgress = item.isInProgress
		binding.imageViewAdd.isVisible = true
		binding.imageViewAdd.isEnabled = !isInProgress
		binding.imageViewAdd.alpha = if (isInProgress) 0.45f else 1f
		binding.progressIcon.isVisible = isInProgress
		binding.imageViewSettings.isVisible = item.action != SourceCatalogItem.Extension.Action.INSTALL && item.sourceName != null
		// Hide/unhide toggle for installed extensions only.
		val isInstalled = item.action != SourceCatalogItem.Extension.Action.INSTALL
		binding.imageViewHide.isVisible = isInstalled
		if (isInstalled) {
			val hideButton = binding.imageViewHide
			hideButton.setIconResource(if (item.isHidden) R.drawable.ic_eye_off else R.drawable.ic_eye)
			val hideDescription = context.getString(if (item.isHidden) R.string.unhide else R.string.hide)
			hideButton.contentDescription = hideDescription
			TooltipCompat.setTooltipText(hideButton, hideDescription)

			// Leave the shape alone while the tap-initiated morph for THIS extension is running;
			// otherwise snap to the correct shape (first show, or row recycled for another row).
			val runningAnim = hideButton.getTag(R.id.tag_hide_corner_anim) as? ValueAnimator
			val morphInProgress = runningAnim?.isRunning == true &&
				hideButton.getTag(R.id.tag_hide_state) == item.packageName
			if (!morphInProgress) {
				runningAnim?.cancel()
				hideButton.cornerRadius = if (item.isHidden) hideSquareRadius else hideCircleRadius
			}
		}
		binding.root.updatePaddingRelative(end = compactEndPadding)
		binding.imageViewAdd.setIconResource(item.action.iconRes)
		val actionDescription = if (isInProgress) {
			context.getString(R.string.in_progress)
		} else {
			context.getString(item.action.titleRes)
		}
		binding.imageViewAdd.contentDescription = actionDescription
		TooltipCompat.setTooltipText(binding.imageViewAdd, actionDescription)
		binding.textViewTitle.text = item.title
		binding.textViewDescription.text = item.subtitle
		binding.textViewDescription.drawableStart = null
		binding.imageViewIcon.applyExternalSourceStyle(true)
		val sourceIconName = item.sourceIconName
		val iconUrl = item.iconUrl
		if (sourceIconName != null) {
			binding.imageViewIcon.setImageAsync(MangaSource(sourceIconName))
		} else if (iconUrl != null) {
			binding.imageViewIcon.setImageFromUrlAsync(
				url = iconUrl,
				fallbackName = item.packageName,
			)
		} else {
			binding.imageViewIcon.setImageDrawable(
				FaviconDrawable(
					context = context,
					styleResId = R.style.FaviconDrawable_Small,
					name = item.packageName,
				),
			)
		}
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyCardBinding>(
	{ inflater, parent -> ItemEmptyCardBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}

