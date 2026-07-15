package org.koitharu.kotatsu.reader.ui.epub

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Layout
import android.text.Spanned
import android.text.SpannedString
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.isNightMode
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentReaderEpubBinding
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import com.google.android.material.R as materialR

/**
 * Native EPUB flow. Chapters are virtualized by RecyclerView in vertical mode and converted to a
 * flat, chapter-spanning ViewPager2 page list in paged mode. Both modes navigate with the same
 * chapter/character locator, so changing modes never reloads a chapter or loses the position.
 */
@AndroidEntryPoint
class EpubReaderFragment : BaseReaderFragment<FragmentReaderEpubBinding>() {

	@Inject
	lateinit var settings: AppSettings

	private var chapters: List<NativeChapter> = emptyList()
	private var verticalView: RecyclerView? = null
	private var pagerView: ViewPager2? = null
	private var pages: List<NativePage> = emptyList()
	private var paginationKey: String? = null
	private var pageRange: IntRange? = null
	private var extendingPages = false
	private var lastLocator = Locator(0, 0)
	private var loading = false
	private var restoring = false
	private var progressScheduled = false
	private var renderGeneration = 0
	private var repaginatePending = false
	private var reflowLocator: Locator? = null

	private val rebuildRunnable = Runnable {
		val repaginate = repaginatePending
		repaginatePending = false
		val locator = reflowLocator
		reflowLocator = null
		if (chapters.isNotEmpty()) refreshReader(repaginate, locator ?: currentLocator())
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentReaderEpubBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentReaderEpubBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		settings.observeAsFlow(AppSettings.KEY_EPUB_THEME) { epubTheme }
			.observe(viewLifecycleOwner) { scheduleRefresh(false) }
		settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_SIZE) { epubFontSize }
			.observe(viewLifecycleOwner) { scheduleRefresh(true) }
		settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_FAMILY) { epubFontFamily }
			.observe(viewLifecycleOwner) { scheduleRefresh(true) }
		settings.observeAsFlow(AppSettings.KEY_EPUB_LINE_HEIGHT) { epubLineHeight }
			.observe(viewLifecycleOwner) { scheduleRefresh(true) }
		settings.observeAsFlow(AppSettings.KEY_EPUB_HORIZONTAL_PADDING) { epubHorizontalPadding }
			.observe(viewLifecycleOwner) { scheduleRefresh(true) }
		settings.observeAsFlow(AppSettings.KEY_EPUB_TEXT_ALIGN) { epubTextAlign }
			.observe(viewLifecycleOwner) { scheduleRefresh(true) }
		settings.observeAsFlow(AppSettings.KEY_EPUB_READING_MODE) { epubReadingMode }
			.observe(viewLifecycleOwner) {
				binding.root.requestApplyInsets()
				scheduleRefresh(false)
			}
	}

	override fun onDestroyView() {
		viewBinding?.root?.removeCallbacks(rebuildRunnable)
		verticalView = null
		pagerView = null
		pages = emptyList()
		paginationKey = null
		pageRange = null
		reflowLocator = null
		super.onDestroyView()
	}

	override fun onCreateAdapter(): BaseReaderAdapter<*>? = null

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		// Reader controls overlay the book. Reserving their size here leaves permanent blank bands
		// whenever the controls are hidden.
		v.updatePadding(0, 0, 0, 0)
		return insets
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {
		val mangaChapters = viewModel.getMangaOrNull()?.chapters.orEmpty()
		if (mangaChapters.isEmpty()) return
		if (chapters.isEmpty() && !loading) {
			loading = true
			try {
				chapters = withContext(Dispatchers.IO) { parseChapters(mangaChapters) }
			} finally {
				loading = false
			}
		}
		if (chapters.isEmpty()) return
		val state = pendingState ?: viewModel.getCurrentState()
		val chapter = state?.chapterId?.let { id -> chapters.indexOfFirst { it.id == id } }
			?.takeIf { it >= 0 } ?: lastLocator.chapter.coerceIn(chapters.indices)
		val offset = state?.scroll?.let { pm -> chapters[chapter].text.length * pm.coerceIn(0, 1000) / 1000 }
			?: lastLocator.offset
		renderMode(Locator(chapter, offset))
	}

	private fun parseChapters(source: List<MangaChapter>): List<NativeChapter> {
		val archives = HashMap<File, ZipFile>()
		return try {
			source.map { chapter ->
				val uri = chapter.url.toUri()
				val file = File(uri.schemeSpecificPart)
				val zip = archives.getOrPut(file) { ZipFile(file) }
				val entryName = uri.fragment.orEmpty()
				val entry = zip.getEntry(entryName) ?: zip.getEntry(entryName.removePrefix("/"))
				val raw = entry?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }.orEmpty()
				val document = Jsoup.parse(raw)
				document.select("script,style,noscript").remove()
				val parsed = HtmlCompat.fromHtml(document.body().html(), HtmlCompat.FROM_HTML_MODE_LEGACY)
				val text = SpannedString(parsed.trimmed())
				NativeChapter(
					id = chapter.id,
					title = chapter.title.orEmpty(),
					text = if (text.isNotEmpty()) text else SpannedString("\u2014"),
				)
			}
		} finally {
			archives.values.forEach(ZipFile::close)
		}
	}

	private fun Spanned.trimmed(): CharSequence {
		var start = 0
		var end = length
		while (start < end && this[start].isWhitespace()) start++
		while (end > start && this[end - 1].isWhitespace()) end--
		return subSequence(start, end)
	}

	private fun scheduleRefresh(repaginate: Boolean) {
		val root = viewBinding?.root ?: return
		repaginatePending = repaginatePending || repaginate
		if (repaginate && reflowLocator == null && chapters.isNotEmpty()) reflowLocator = currentLocator()
		root.removeCallbacks(rebuildRunnable)
		if (repaginate) {
			refreshVisibleStyles()
			root.postDelayed(rebuildRunnable, REPAGINATE_DELAY_MS)
		} else {
			root.post(rebuildRunnable)
		}
	}

	private fun refreshVisibleStyles() {
		viewBinding?.readerContainer?.setBackgroundColor(backgroundColor)
		verticalView?.apply {
			setBackgroundColor(backgroundColor)
			adapter?.notifyDataSetChanged()
		}
		pagerView?.adapter?.notifyDataSetChanged()
	}

	private fun refreshReader(repaginate: Boolean, locator: Locator) {
		if (isPagedMode) {
			if (!repaginate && pagerView != null) {
				viewBinding?.readerContainer?.setBackgroundColor(backgroundColor)
				pagerView?.adapter?.notifyDataSetChanged()
				return
			}
			if (repaginate) paginationKey = null
			renderPaged(viewBinding?.readerContainer ?: return, locator)
		} else if (verticalView != null) {
			verticalView?.setBackgroundColor(backgroundColor)
			verticalView?.adapter?.notifyDataSetChanged()
			goTo(locator)
		} else {
			renderMode(locator)
		}
	}

	private fun renderMode(locator: Locator) {
		val container = viewBinding?.readerContainer ?: return
		if (container.width == 0 || container.height == 0) {
			container.post { renderMode(locator) }
			return
		}
		lastLocator = locator.clamped()
		if (!isPagedMode && verticalView != null) {
			goTo(lastLocator)
			return
		}
		if (isPagedMode && pagerView != null && pages.any {
				it.chapter == lastLocator.chapter && lastLocator.offset in it.start until it.end
			}
		) {
			goTo(lastLocator)
			return
		}
		restoring = true
		renderGeneration++
		if (isPagedMode) {
			renderPaged(container, lastLocator)
		} else {
			container.removeAllViews()
			verticalView = null
			pagerView = null
			renderVertical(container, lastLocator)
		}
	}

	private fun renderVertical(container: FrameLayout, locator: Locator) {
		val recycler = RecyclerView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			layoutManager = LinearLayoutManager(context)
			adapter = ChapterAdapter()
			itemAnimator = null
			overScrollMode = View.OVER_SCROLL_NEVER
			isClickable = false
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			setBackgroundColor(backgroundColor)
			addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = scheduleProgress()
			})
		}
		verticalView = recycler
		container.addView(recycler)
		val manager = recycler.layoutManager as LinearLayoutManager
		manager.scrollToPositionWithOffset(locator.chapter, 0)
		recycler.post {
			val child = manager.findViewByPosition(locator.chapter)
			if (child != null) {
				val fraction = locator.offset.toFloat() / chapters[locator.chapter].text.length.coerceAtLeast(1)
				recycler.scrollBy(0, (child.height * fraction).toInt())
			}
			restoring = false
			notifyProgress()
		}
	}

	private fun renderPaged(container: FrameLayout, locator: Locator) {
		val generation = ++renderGeneration
		val key = "${container.width}:${container.height}:${settings.epubFontSize}:${settings.epubFontFamily}:" +
			"${settings.epubLineHeight}:${settings.epubHorizontalPadding}:$effectiveTextAlign:${settings.epubReadingMode}"
		container.setBackgroundColor(backgroundColor)
		if (pages.isNotEmpty() && paginationKey == key && pageRange?.contains(locator.chapter) == true) {
			renderPagedReady(container, locator)
			return
		}
		val range = (locator.chapter - PAGE_LOOKAHEAD).coerceAtLeast(0)..
			(locator.chapter + PAGE_LOOKAHEAD).coerceAtMost(chapters.lastIndex)
		viewLifecycleOwner.lifecycleScope.launch {
			val newPages = withContext(Dispatchers.Default) { paginate(container.width, container.height, range) }
			if (generation != renderGeneration || !isPagedMode || viewBinding?.readerContainer !== container) return@launch
			pages = newPages
			paginationKey = key
			pageRange = range
			renderPagedReady(container, locator)
		}
	}

	private fun renderPagedReady(container: FrameLayout, locator: Locator) {
		restoring = true
		container.removeAllViews()
		verticalView = null
		pagerView = null
		val pager = ViewPager2(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			orientation = ViewPager2.ORIENTATION_HORIZONTAL
			layoutDirection = if (isRtlPagedMode) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
			adapter = PageAdapter()
			overScrollMode = View.OVER_SCROLL_NEVER
			isClickable = false
			isFocusable = false
			registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
				if (!restoring) {
					notifyProgress()
					extendPageWindow(position)
				}
			}
			})
		}
		(pager.getChildAt(0) as? RecyclerView)?.apply {
			isClickable = false
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		}
		pagerView = pager
		container.addView(pager)
		val target = pages.indexOfFirst {
			it.chapter == locator.chapter && locator.offset >= it.start && locator.offset < it.end
		}.takeIf { it >= 0 } ?: pages.indexOfLast { it.chapter <= locator.chapter }.coerceAtLeast(0)
		pager.setCurrentItem(target, false)
		pager.post {
			restoring = false
			notifyProgress()
			extendPageWindow(pager.currentItem)
		}
	}

	private fun extendPageWindow(position: Int) {
		if (extendingPages) return
		val range = pageRange ?: return
		val chapter = pages.getOrNull(position)?.chapter ?: return
		val prepend = chapter == range.first && range.first > 0
		val append = chapter == range.last && range.last < chapters.lastIndex
		if (!prepend && !append) return
		val target = if (prepend) range.first - 1 else range.last + 1
		val pager = pagerView ?: return
		val generation = renderGeneration
		extendingPages = true
		viewLifecycleOwner.lifecycleScope.launch {
			val added = withContext(Dispatchers.Default) { paginate(pager.width, pager.height, target..target) }
			if (generation == renderGeneration && pagerView === pager) {
				val adapter = pager.adapter ?: run { extendingPages = false; return@launch }
				if (prepend) {
					restoring = true
					pages = added + pages
					pageRange = target..range.last
					adapter.notifyItemRangeInserted(0, added.size)
					pager.setCurrentItem(position + added.size, false)
					pager.post { restoring = false; notifyProgress() }
				} else {
					val start = pages.size
					pages = pages + added
					pageRange = range.first..target
					adapter.notifyItemRangeInserted(start, added.size)
				}
			}
			extendingPages = false
		}
	}

	private fun paginate(viewWidth: Int, viewHeight: Int, range: IntRange): List<NativePage> {
		val density = resources.displayMetrics.density
		val horizontal = (settings.epubHorizontalPadding * density).toInt().coerceAtLeast(1)
		val vertical = (16 * density).toInt()
		val width = (viewWidth - horizontal * 2).coerceAtLeast(1)
		val height = (viewHeight - vertical * 2).coerceAtLeast(1)
		val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
			color = foregroundColor
			textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics) *
				settings.epubFontSize / 100f
			typeface = readerTypeface
		}
		val result = ArrayList<NativePage>()
		range.forEach { chapterIndex ->
			val chapter = chapters[chapterIndex]
			val layout = StaticLayout.Builder.obtain(chapter.text, 0, chapter.text.length, paint, width)
				.setAlignment(textAlignment)
				.setTextDirection(if (isRtlPagedMode) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
				.setIncludePad(true)
				.setLineSpacing(0f, settings.epubLineHeight / 100f)
				.apply {
					if (android.os.Build.VERSION.SDK_INT >= 26 && effectiveTextAlign == "justify") {
						setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
					}
				}.build()
			var start = 0
			while (start < chapter.text.length) {
				val firstLine = layout.getLineForOffset(start)
				val bottom = (layout.getLineTop(firstLine) + height).coerceAtMost(layout.height)
				val lastLine = layout.getLineForVertical((bottom - 1).coerceAtLeast(0))
				val end = layout.getLineEnd(lastLine).coerceAtLeast(start + 1).coerceAtMost(chapter.text.length)
				result += NativePage(chapterIndex, start, end)
				start = end
			}
		}
		return result.ifEmpty {
			val chapter = range.first.coerceIn(chapters.indices)
			listOf(NativePage(chapter, 0, chapters[chapter].text.length))
		}
	}

	private fun createTextView(parent: ViewGroup, paged: Boolean): TextView = TextView(parent.context).apply {
		layoutParams = if (paged) ViewGroup.LayoutParams(-1, -1) else ViewGroup.LayoutParams(-1, -2)
		applyTextStyle(this, paged)
		isClickable = false
		isLongClickable = false
	}

	private fun applyTextStyle(textView: TextView, paged: Boolean) = with(textView) {
		setTextColor(foregroundColor)
		setBackgroundColor(backgroundColor)
		textSize = 16f * settings.epubFontSize / 100f
		typeface = readerTypeface
		setLineSpacing(0f, settings.epubLineHeight / 100f)
		includeFontPadding = true
		textDirection = if (isRtlPagedMode) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
		gravity = Gravity.TOP or when (effectiveTextAlign) {
			"center" -> Gravity.CENTER_HORIZONTAL
			"right", "end" -> Gravity.RIGHT
			"justify" -> if (isRtlPagedMode) Gravity.RIGHT else Gravity.LEFT
			else -> Gravity.START
		}
		textAlignment = when (effectiveTextAlign) {
			"center" -> View.TEXT_ALIGNMENT_CENTER
			"right", "end" -> View.TEXT_ALIGNMENT_VIEW_END
			"justify" -> if (isRtlPagedMode) View.TEXT_ALIGNMENT_TEXT_START else View.TEXT_ALIGNMENT_VIEW_START
			else -> View.TEXT_ALIGNMENT_VIEW_START
		}
		if (android.os.Build.VERSION.SDK_INT >= 26 && effectiveTextAlign == "justify") {
			justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
		}
		val density = resources.displayMetrics.density
		val h = (settings.epubHorizontalPadding * density).toInt()
		val v = ((if (paged) 16 else 20) * density).toInt()
		setPadding(h, v, h, if (paged) v else (36 * density).toInt())
	}

	private fun currentLocator(): Locator {
		pagerView?.let { pager ->
			return pages.getOrNull(pager.currentItem)?.let { Locator(it.chapter, it.start) } ?: lastLocator
		}
		verticalView?.let { recycler ->
			val manager = recycler.layoutManager as? LinearLayoutManager ?: return lastLocator
			val index = manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return lastLocator
			val child = manager.findViewByPosition(index) ?: return Locator(index, 0)
			val visibleOffset = (-child.top).coerceAtLeast(0)
			val charOffset = (chapters[index].text.length.toLong() * visibleOffset / child.height.coerceAtLeast(1)).toInt()
			return Locator(index, charOffset).clamped()
		}
		return lastLocator.clamped()
	}

	private fun scheduleProgress() {
		if (restoring || progressScheduled) return
		progressScheduled = true
		viewBinding?.root?.postDelayed({
			progressScheduled = false
			notifyProgress()
		}, PROGRESS_INTERVAL_MS)
	}

	private fun notifyProgress() {
		if (chapters.isEmpty()) return
		val locator = currentLocator().clamped()
		lastLocator = locator
		val chapter = chapters[locator.chapter]
		val chapterPm = locator.offset * 1000 / chapter.text.length.coerceAtLeast(1)
		val globalPage = pagerView?.currentItem ?: 0
		val firstChapterPage = if (pagerView != null) pages.indexOfFirst { it.chapter == locator.chapter }.coerceAtLeast(0) else 0
		val page = globalPage - firstChapterPage
		val pageCount = if (pagerView != null) pages.count { it.chapter == locator.chapter } else 0
		viewModel.onEpubProgressChanged(chapter.id, chapterPm, page, pageCount)
	}

	override fun getCurrentState(): ReaderState? {
		if (chapters.isEmpty()) return null
		val locator = currentLocator().clamped()
		val chapter = chapters[locator.chapter]
		return ReaderState(chapter.id, 0, locator.offset * 1000 / chapter.text.length.coerceAtLeast(1))
	}

	override fun switchPageBy(delta: Int) {
		pagerView?.let {
			it.setCurrentItem((it.currentItem + delta).coerceIn(0, pages.lastIndex), isAnimationEnabled())
			return
		}
		verticalView?.smoothScrollBy(0, (verticalView?.height ?: 0) * 9 / 10 * delta)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		pagerView?.let {
			val chapter = pages.getOrNull(it.currentItem)?.chapter ?: return
			val first = pages.indexOfFirst { page -> page.chapter == chapter }.coerceAtLeast(0)
			val count = pages.count { page -> page.chapter == chapter }
			it.setCurrentItem(first + position.coerceIn(0, count - 1), smooth && isAnimationEnabled())
			return
		}
		val chapter = currentLocator().chapter
		val offset = chapters[chapter].text.length * position.coerceIn(0, 1000) / 1000
		goTo(Locator(chapter, offset), smooth)
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		val recycler = verticalView ?: return false
		if (!recycler.canScrollVertically(delta)) return false
		if (smooth) recycler.smoothScrollBy(0, delta) else recycler.scrollBy(0, delta)
		return true
	}

	private fun goTo(locator: Locator, smooth: Boolean = false) {
		lastLocator = locator.clamped()
		if (pagerView != null) {
			val page = pages.indexOfFirst { it.chapter == lastLocator.chapter && lastLocator.offset in it.start until it.end }
			if (page >= 0) pagerView?.setCurrentItem(page, smooth && isAnimationEnabled())
		} else {
			val recycler = verticalView ?: return
			val manager = recycler.layoutManager as LinearLayoutManager
			manager.scrollToPositionWithOffset(lastLocator.chapter, 0)
			recycler.post {
				val child = manager.findViewByPosition(lastLocator.chapter) ?: return@post
				val fraction = lastLocator.offset.toFloat() / chapters[lastLocator.chapter].text.length.coerceAtLeast(1)
				recycler.scrollBy(0, (child.height * fraction).toInt())
				notifyProgress()
			}
		}
	}

	override fun onZoomIn() = Unit
	override fun onZoomOut() = Unit

	fun showBookSearch() {
		val input = TextInputEditText(requireContext()).apply { setSingleLine() }
		val field = TextInputLayout(requireContext()).apply {
			hint = getString(R.string.epub_search_hint)
			boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
			setStartIconDrawable(R.drawable.ic_search)
			addView(input)
		}
		val container = FrameLayout(requireContext()).apply {
			val margin = (24 * resources.displayMetrics.density).toInt()
			setPadding(margin, 8, margin, 0)
			addView(field, FrameLayout.LayoutParams(-1, -2))
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.epub_search_book).setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.search) { _, _ -> searchBook(input.text.toString().trim()) }.show()
	}

	private fun searchBook(query: String) {
		if (query.isEmpty() || chapters.isEmpty()) return
		Toast.makeText(requireContext(), R.string.loading_, Toast.LENGTH_SHORT).show()
		viewLifecycleOwner.lifecycleScope.launch {
			val results = withContext(Dispatchers.Default) {
				chapters.mapIndexedNotNull { index, chapter ->
					val match = chapter.text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return@mapIndexedNotNull null
					val start = (match - 45).coerceAtLeast(0)
					val end = (match + query.length + 70).coerceAtMost(chapter.text.length)
					SearchResult(index, chapter.title, chapter.text.substring(start, end).replace(Regex("\\s+"), " ").trim(), match)
				}.take(MAX_SEARCH_RESULTS)
			}
			if (results.isEmpty()) {
				Toast.makeText(requireContext(), R.string.epub_no_search_results, Toast.LENGTH_SHORT).show()
				return@launch
			}
			val adapter = object : ArrayAdapter<SearchResult>(requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, results) {
				override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
					val row = super.getView(position, convertView, parent)
					val result = getItem(position) ?: return row
					row.findViewById<TextView>(android.R.id.text1).text = result.title.ifEmpty { getString(R.string.epub_untitled_chapter) }
					row.findViewById<TextView>(android.R.id.text2).apply { text = result.snippet; maxLines = 2 }
					return row
				}
			}
			val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.search_results)
				.setAdapter(adapter) { _, index -> goTo(Locator(results[index].chapter, results[index].offset)) }
				.setNegativeButton(android.R.string.cancel, null).show()
			dialog.listView.apply {
				divider = ColorDrawable(requireContext().getThemeColor(materialR.attr.colorOutlineVariant, Color.GRAY))
				dividerHeight = resources.displayMetrics.density.toInt().coerceAtLeast(1)
			}
		}
	}

	private val isPagedMode get() = settings.epubReadingMode != EPUB_MODE_SCROLL
	private val isRtlPagedMode get() = settings.epubReadingMode == EPUB_MODE_PAGED_RTL
	private val effectiveTextAlign get() = if (isRtlPagedMode) "justify" else settings.epubTextAlign
	private val backgroundColor: Int get() {
		val dark = when (settings.epubTheme) {
			"light" -> false
			"dark" -> true
			"black" -> return Color.BLACK
			else -> resources.isNightMode
		}
		return ContextThemeWrapper(requireContext(), if (dark) materialR.style.ThemeOverlay_Material3_Dark else materialR.style.ThemeOverlay_Material3_Light)
			.getThemeColor(android.R.attr.colorBackground, if (dark) Color.BLACK else Color.WHITE)
	}
	private val foregroundColor get() = if (ColorUtils.calculateLuminance(backgroundColor) > .5) 0xFF1B1B1F.toInt() else 0xFFE4E4E8.toInt()
	private val readerTypeface get() = Typeface.create(settings.epubFontFamily.substringBefore(',').trim().trim('\'', '"'), Typeface.NORMAL)
	private val textAlignment get() = when (effectiveTextAlign) {
		"center" -> Layout.Alignment.ALIGN_CENTER
		"right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
		"justify" -> Layout.Alignment.ALIGN_NORMAL
		else -> Layout.Alignment.ALIGN_NORMAL
	}

	private inner class ChapterAdapter : RecyclerView.Adapter<TextHolder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(createTextView(parent, false))
		override fun getItemCount() = chapters.size
		override fun onBindViewHolder(holder: TextHolder, position: Int) {
			applyTextStyle(holder.text, false)
			holder.text.text = chapters[position].text
		}
	}

	private inner class PageAdapter : RecyclerView.Adapter<TextHolder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(createTextView(parent, true))
		override fun getItemCount() = pages.size
		override fun onBindViewHolder(holder: TextHolder, position: Int) {
			val page = pages[position]
			applyTextStyle(holder.text, true)
			holder.text.text = chapters[page.chapter].text.subSequence(page.start, page.end)
		}
	}

	private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)
	private data class NativeChapter(val id: Long, val title: String, val text: Spanned)
	private data class NativePage(val chapter: Int, val start: Int, val end: Int)
	private data class SearchResult(val chapter: Int, val title: String, val snippet: String, val offset: Int)
	private data class Locator(val chapter: Int, val offset: Int)
	private fun Locator.clamped(): Locator {
		if (chapters.isEmpty()) return Locator(0, 0)
		val c = chapter.coerceIn(chapters.indices)
		return Locator(c, offset.coerceIn(0, chapters[c].text.length.coerceAtLeast(1) - 1))
	}

	companion object {
		private const val EPUB_MODE_SCROLL = "scroll"
		private const val EPUB_MODE_PAGED_RTL = "paged_rtl"
		private const val MAX_SEARCH_RESULTS = 100
		private const val PROGRESS_INTERVAL_MS = 50L
		private const val PAGE_LOOKAHEAD = 1
		private const val REPAGINATE_DELAY_MS = 180L
	}
}
