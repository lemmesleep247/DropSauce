package org.koitharu.kotatsu.core.exceptions.resolve

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.os.Build
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import com.google.android.material.R as materialR

private const val COPY_ERROR_ACTION_TAG = "copy_error_action"

fun Snackbar.addCopyErrorAction(error: Throwable): Snackbar {
	val snackbarView = view as? ViewGroup ?: return this
	val content = snackbarView.findFirstChildViewGroup() ?: return this
	if (content.findViewWithTag<View>(COPY_ERROR_ACTION_TAG) != null) {
		return this
	}
	val originalAction = content.findViewById<Button>(materialR.id.snackbar_action)
	// Button creation requires a fully-themed context. If the snackbar context doesn't
	// carry the complete Material theme (e.g. in certain overlay/dialog scenarios), the
	// constructor throws UnsupportedOperationException trying to resolve dimension attrs.
	// Silently skip the copy action rather than crashing the app.
	val copyAction = runCatching {
		AppCompatButton(context, null, materialR.attr.snackbarButtonStyle)
	}.getOrNull() ?: return this
	copyAction.apply {
		tag = COPY_ERROR_ACTION_TAG
		text = context.getString(androidx.preference.R.string.copy)
		isAllCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) originalAction?.isAllCaps ?: true else true
		minWidth = 0
		minHeight = originalAction?.minHeight ?: 0
		minimumWidth = 0
		minimumHeight = originalAction?.minimumHeight ?: 0
		originalAction?.let {
			setTextColor(it.currentTextColor)
			typeface = it.typeface
		}
		setOnClickListener {
			context.copyToClipboard(context.getString(R.string.error), error.stackTraceToString())
			dismiss()
		}
	}
	val layoutParams = originalAction?.layoutParams?.let {
		when (it) {
			is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(it)
			is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(it)
			else -> ViewGroup.LayoutParams(it)
		}
	} ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	val index = originalAction?.let { content.indexOfChild(it) + 1 } ?: content.childCount
	content.addView(copyAction, index, layoutParams)
	return this
}

private fun ViewGroup.findFirstChildViewGroup(): ViewGroup? {
	for (i in 0 until childCount) {
		val child = getChildAt(i)
		if (child is ViewGroup) {
			return child
		}
	}
	return null
}
