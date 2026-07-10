package org.koitharu.kotatsu.reader.ui

import android.content.res.Configuration
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.reader.ui.epub.EpubReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.doublepage.DoubleReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.doublereversed.ReversedDoubleReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.reversed.ReversedReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.standard.PagerReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.vertical.VerticalReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonReaderFragment
import java.util.EnumMap

class ReaderManager(
	private val fragmentManager: FragmentManager,
	private val container: FragmentContainerView,
	settings: AppSettings,
) {

	private val modeMap = EnumMap<ReaderMode, Class<out BaseReaderFragment<*>>>(ReaderMode::class.java)

	init {
		val useDoublePages = isLandscape() && settings.isReaderDoubleOnLandscape
		invalidateTypesMap(useDoublePages)
	}

	// EPUB books always use the single text reader fragment; the selected ReaderMode only
	// switches its paged/scroll behavior, so track it here instead of a mode->class lookup
	var isEpub: Boolean = false
	private var epubMode: ReaderMode? = null

	val currentReader: BaseReaderFragment<*>?
		get() = fragmentManager.findFragmentById(container.id) as? BaseReaderFragment<*>

	val currentMode: ReaderMode?
		get() {
			val readerClass = currentReader?.javaClass ?: return null
			return if (readerClass == EpubReaderFragment::class.java) {
				epubMode
			} else {
				modeMap.findKeyByValue(readerClass)
			}
		}

	fun replace(newMode: ReaderMode) {
		val readerClass = if (isEpub) {
			epubMode = newMode
			EpubReaderFragment::class.java
		} else {
			epubMode = null
			requireNotNull(modeMap[newMode])
		}
		fragmentManager.commit {
			setReorderingAllowed(true)
			replace(container.id, readerClass, null, null)
		}
	}

	fun setDoubleReaderMode(isEnabled: Boolean) {
		if (isEpub) {
			return
		}
		val mode = currentMode
		val prevReader = currentReader?.javaClass
		invalidateTypesMap(isEnabled)
		val newReader = modeMap[mode]
		if (mode != null && newReader != prevReader) {
			replace(mode)
		}
	}

	private fun invalidateTypesMap(useDoublePages: Boolean) {
		modeMap[ReaderMode.STANDARD] = if (useDoublePages) {
			DoubleReaderFragment::class.java
		} else {
			PagerReaderFragment::class.java
		}
		modeMap[ReaderMode.REVERSED] = if (useDoublePages) {
			ReversedDoubleReaderFragment::class.java
		} else {
			ReversedReaderFragment::class.java
		}
		modeMap[ReaderMode.WEBTOON] = WebtoonReaderFragment::class.java
		modeMap[ReaderMode.VERTICAL] = VerticalReaderFragment::class.java
	}

	private fun isLandscape() = container.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}
