package org.koitharu.kotatsu.core.util.ext

import android.content.res.ColorStateList
import android.graphics.Bitmap

inline fun <R> Bitmap.use(block: (Bitmap) -> R) = try {
	block(this)
} finally {
	recycle()
}

fun ColorStateList.hasFocusStateSpecified(): Boolean {
	return getColorForState(intArrayOf(android.R.attr.state_focused), defaultColor) != defaultColor
}
