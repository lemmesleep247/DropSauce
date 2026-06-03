package org.koitharu.kotatsu.list.ui.adapter

import android.view.View
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.databinding.ItemTip2Binding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.TipModel

fun tipAD(
	listener: TipView.OnButtonClickListener,
	onClose: ((TipModel) -> Unit)? = null,
) = adapterDelegateViewBinding<TipModel, ListModel, ItemTip2Binding>(
	{ layoutInflater, parent -> ItemTip2Binding.inflate(layoutInflater, parent, false) }
) {

	binding.root.onButtonClickListener = listener
	binding.root.onCloseListener = View.OnClickListener {
		(binding.root.tag as? TipModel)?.let { tip -> onClose?.invoke(tip) }
	}

	bind {
		with(binding.root) {
			tag = item
			setTitle(item.title)
			setText(item.text)
			setIcon(item.icon)
			setPrimaryButtonText(item.primaryButtonText)
			setSecondaryButtonText(item.secondaryButtonText)
			setClosable(onClose != null && item.isClosable)
		}
	}
}
