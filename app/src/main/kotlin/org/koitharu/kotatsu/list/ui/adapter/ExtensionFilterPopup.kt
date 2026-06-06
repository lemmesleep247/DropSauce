package org.koitharu.kotatsu.list.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.image.FaviconView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeDrawable
import org.koitharu.kotatsu.core.util.ext.getThemeResId
import org.koitharu.kotatsu.core.util.ext.resolveDp
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import com.google.android.material.R as materialR
import androidx.appcompat.R as appcompatR

internal object ExtensionFilterPopup {

	fun show(
		anchor: View,
		filter: ExtensionFilter,
		listener: QuickFilterClickListener,
	) {
		val context = anchor.context
		val rows = ArrayList<Row>(filter.options.size)
		val selectedSourceNames = filter.selectedOptions.mapTo(HashSet()) { it.mangaSource.name }
		val resetButton = createResetButton(context)
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(
				context.resources.resolveDp(18),
				context.resources.resolveDp(16),
				context.resources.resolveDp(12),
				context.resources.resolveDp(12),
			)
			addView(createHeader(context))
			for (option in filter.options) {
				val row = createRow(
					context = context,
					option = option,
					selectedSourceNames = selectedSourceNames,
					listener = listener,
					onSelectionChanged = {
						updateResetButton(resetButton, selectedSourceNames.isNotEmpty())
					},
				)
				rows += row.binding
				addView(row.view)
			}
			addView(
				resetButton,
				LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
					topMargin = context.resources.resolveDp(10)
				},
			)
		}
		resetButton.setOnClickListener {
			selectedSourceNames.clear()
			rows.forEach { it.checkBox.isChecked = false }
			updateResetButton(resetButton, isEnabled = false)
			listener.onFilterOptionsCleared(filter.options)
		}
		updateResetButton(resetButton, selectedSourceNames.isNotEmpty())

		val scrollView = MaxHeightScrollView(context).apply {
			maxHeight = context.resources.resolveDp(420)
			addView(content)
			isFillViewport = false
			clipToPadding = false
		}
		val horizontalMargin = context.resources.resolveDp(32)
		val maxAvailableWidth = context.resources.displayMetrics.widthPixels - horizontalMargin
		val preferredWidth = context.resources.resolveDp(360)
		val minWidth = minOf(context.resources.resolveDp(260), maxAvailableWidth)
		val width = minOf(preferredWidth, maxAvailableWidth).coerceAtLeast(minWidth)
		PopupWindow(scrollView, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
			setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.m3_menu_background))
			isOutsideTouchable = true
			elevation = context.resources.resolveDp(8).toFloat()
			showAsDropDown(anchor, 0, context.resources.resolveDp(4), Gravity.NO_GRAVITY)
		}
	}

	private fun createHeader(context: Context): View {
		return TextView(context).apply {
			setText(R.string.extension_filters)
			setTextAppearanceAttr(materialR.attr.textAppearanceTitleMedium)
			setTextColor(context.getThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
			setPadding(0, 0, 0, context.resources.resolveDp(8))
		}
	}

	private fun createResetButton(context: Context): MaterialButton {
		return MaterialButton(context, null, materialR.attr.materialButtonTonalStyle).apply {
			setText(R.string.reset)
			gravity = Gravity.CENTER
			minimumHeight = context.resources.resolveDp(48)
		}
	}

	private fun createRow(
		context: Context,
		option: ListFilterOption.Source,
		selectedSourceNames: MutableSet<String>,
		listener: QuickFilterClickListener,
		onSelectionChanged: () -> Unit,
	): RowView {
		val row = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = context.resources.resolveDp(56)
			setPadding(0, context.resources.resolveDp(4), context.resources.resolveDp(6), context.resources.resolveDp(4))
			background = context.getThemeDrawable(appcompatR.attr.selectableItemBackground)
		}
		val checkBox = MaterialCheckBox(context).apply {
			isClickable = false
			isFocusable = false
			isChecked = option.mangaSource.name in selectedSourceNames
			minimumWidth = context.resources.resolveDp(48)
			minimumHeight = context.resources.resolveDp(48)
			gravity = Gravity.CENTER
		}
		val icon = FaviconView(context).apply {
			applyExternalSourceStyle(option.mangaSource.isExternalSource())
			setImageAsync(option.mangaSource)
		}
		val title = TextView(context).apply {
			text = option.getMenuTitle(context)
			setTextAppearanceAttr(materialR.attr.textAppearanceBodyLarge)
			setTextColor(context.getThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
			isSingleLine = true
		}
		row.addView(checkBox, LinearLayout.LayoutParams(context.resources.resolveDp(48), context.resources.resolveDp(48)))
		row.addView(icon, LinearLayout.LayoutParams(context.resources.resolveDp(32), context.resources.resolveDp(32)))
		row.addView(
			title,
			LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
				marginStart = context.resources.resolveDp(16)
			},
		)
		val rowBinding = Row(checkBox)
		row.setOnClickListener {
			val checked = !checkBox.isChecked
			checkBox.isChecked = checked
			if (checked) {
				selectedSourceNames += option.mangaSource.name
			} else {
				selectedSourceNames -= option.mangaSource.name
			}
			listener.onFilterOptionChanged(option, checked)
			onSelectionChanged()
		}
		return RowView(row, rowBinding)
	}

	private fun updateResetButton(resetButton: MaterialButton, isEnabled: Boolean) {
		resetButton.isEnabled = isEnabled
	}

	private fun ListFilterOption.Source.getMenuTitle(context: Context): String {
		return when (val source = mangaSource.unwrap()) {
			is MihonMangaSource -> if (source.hasLanguageSuffix) {
				"${source.displayName} (${source.languageDisplayName})"
			} else {
				source.displayName
			}

			else -> mangaSource.getTitle(context)
		}
	}

	private fun TextView.setTextAppearanceAttr(@AttrRes attr: Int) {
		val style = context.getThemeResId(attr, 0)
		if (style != 0) {
			setTextAppearance(style)
		}
	}

	private class MaxHeightScrollView(context: Context) : ScrollView(context) {

		var maxHeight = 0

		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			val resolvedHeight = if (maxHeight > 0) {
				MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
			} else {
				heightMeasureSpec
			}
			super.onMeasure(widthMeasureSpec, resolvedHeight)
		}
	}

	private data class Row(
		val checkBox: MaterialCheckBox,
	)

	private data class RowView(
		val view: View,
		val binding: Row,
	)
}
