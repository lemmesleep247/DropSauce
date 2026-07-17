package org.koitharu.kotatsu.filter.ui

import android.content.DialogInterface
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH

fun Fragment.showSaveFilterDialog(filter: FilterCoordinator) {
    lateinit var input: EditText
    val dialog = buildAlertDialog(requireContext()) {
        input = setEditText(
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES,
            singleLine = true,
        )
        input.filters += InputFilter.LengthFilter(MAX_TITLE_LENGTH)
        input.setHint(R.string.enter_name)
        setTitle(R.string.save_filter)
        setPositiveButton(R.string.save, null)
        setNegativeButton(android.R.string.cancel, null)
    }
    dialog.setOnShowListener {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            when {
                name.isEmpty() -> input.error = getString(R.string.invalid_value_message)
                filter.isSavedFilterNameTaken(name) -> input.error = getString(R.string.filter_name_exists)
                else -> {
                    filter.saveCurrentFilter(name)
                    dialog.dismiss()
                }
            }
        }
    }
    dialog.show()
}
