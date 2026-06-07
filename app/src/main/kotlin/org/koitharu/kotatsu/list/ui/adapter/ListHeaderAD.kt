package org.koitharu.kotatsu.list.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import com.google.android.material.badge.BadgeDrawable
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHeaderBinding
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

fun listHeaderAD(
	listener: ListHeaderClickListener?,
) = adapterDelegateViewBinding<ListHeader, ListModel, ItemHeaderBinding>(
	{ inflater, parent -> ItemHeaderBinding.inflate(inflater, parent, false) },
) {
	var badge: BadgeDrawable? = null
	val defaultButtonMinHeight = binding.buttonMore.minHeight
	val defaultButtonMinimumHeight = binding.buttonMore.minimumHeight
	val defaultRootPaddingBottom = binding.root.paddingBottom
	val defaultButtonMarginEnd = (binding.buttonMore.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0

	if (listener != null) {
		binding.buttonMore.setOnClickListener {
			listener.onListHeaderClick(item, it)
		}
	}

	bind {
		val currentItem = item
		binding.textViewTitle.text = currentItem.getText(context)
		if (currentItem.buttonTextRes == 0) {
			binding.root.updatePadding(bottom = defaultRootPaddingBottom)
			binding.buttonMore.updateLayoutParams<ViewGroup.MarginLayoutParams> {
				marginEnd = defaultButtonMarginEnd
			}
			binding.buttonMore.isGone = true
			binding.buttonMore.text = null
			binding.buttonMore.clearBadge(badge)
		} else {
			val isUpdateAll = currentItem.buttonTextRes == R.string.update_all
			binding.root.updatePadding(
				bottom = if (isUpdateAll) {
					context.resources.getDimensionPixelSize(R.dimen.margin_small)
				} else {
					defaultRootPaddingBottom
				},
			)
			binding.buttonMore.icon = null
			binding.buttonMore.setText(currentItem.buttonTextRes)
			binding.buttonMore.contentDescription = context.getString(currentItem.buttonTextRes)
			binding.buttonMore.isGone = false
			if (isUpdateAll) {
				val height = context.resources.getDimensionPixelSize(R.dimen.extension_update_all_button_height)
				binding.buttonMore.minHeight = height
				binding.buttonMore.minimumHeight = height
				binding.buttonMore.updateLayoutParams<ViewGroup.LayoutParams> {
					this.width = context.resources.getDimensionPixelSize(R.dimen.extension_action_button_group_width)
					this.height = ViewGroup.LayoutParams.WRAP_CONTENT
				}
				binding.buttonMore.updateLayoutParams<ViewGroup.MarginLayoutParams> {
					marginEnd = context.resources.getDimensionPixelSize(R.dimen.margin_normal)
				}
			} else {
				binding.buttonMore.minHeight = defaultButtonMinHeight
				binding.buttonMore.minimumHeight = defaultButtonMinimumHeight
				binding.buttonMore.updateLayoutParams<ViewGroup.LayoutParams> {
					width = ViewGroup.LayoutParams.WRAP_CONTENT
					height = ViewGroup.LayoutParams.WRAP_CONTENT
				}
				binding.buttonMore.updateLayoutParams<ViewGroup.MarginLayoutParams> {
					marginEnd = defaultButtonMarginEnd
				}
			}
			val primaryColor = MaterialColors.getColor(binding.buttonMore, appcompatR.attr.colorPrimary)
			when {
				currentItem.buttonTextRes == R.string.update_all -> {
					binding.buttonMore.backgroundTintList = ColorStateList.valueOf(primaryColor)
					binding.buttonMore.strokeWidth = 0
					binding.buttonMore.strokeColor = null
					binding.buttonMore.setTextColor(
						MaterialColors.getColor(binding.buttonMore, com.google.android.material.R.attr.colorOnPrimary),
					)
				}

				currentItem.buttonStyle == ListHeader.ButtonStyle.OUTLINED -> {
					binding.buttonMore.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
					binding.buttonMore.strokeWidth =
						context.resources.displayMetrics.density.toInt().coerceAtLeast(1)
					binding.buttonMore.strokeColor = ColorStateList.valueOf(primaryColor)
					binding.buttonMore.setTextColor(primaryColor)
				}

				else -> {
					binding.buttonMore.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
					binding.buttonMore.strokeWidth = 0
					binding.buttonMore.strokeColor = null
					binding.buttonMore.setTextColor(primaryColor)
				}
			}
			badge = itemView.bindBadge(badge, currentItem.badge)
		}
	}
}
