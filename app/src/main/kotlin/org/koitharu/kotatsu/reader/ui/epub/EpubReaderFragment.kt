package org.koitharu.kotatsu.reader.ui.epub

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.isNightMode
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.toZipUri
import org.koitharu.kotatsu.databinding.FragmentReaderEpubBinding
import org.koitharu.kotatsu.local.data.input.EpubBook
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Text reader for EPUB books with scrolling and swipe-paged modes.
 *
 * Chapter switching is seamless in both directions: the adjacent chapters are preloaded and spliced
 * into the same WebView document, separated by boundary markers. At most three chapter blocks live in
 * the DOM at once ([prev][current][next]); crossing a boundary promotes the neighbour to current and
 * trims the far block, compensating the scroll/transform so nothing visibly jumps. The current
 * chapter's extent is always re-derived from the marker positions, so it survives resizes.
 */
@AndroidEntryPoint
class EpubReaderFragment : BaseReaderFragment<FragmentReaderEpubBinding>() {

	@Inject
	lateinit var settings: AppSettings

	@Volatile
	private var currentChapterId = 0L

	@Volatile
	private var currentHref: String? = null

	@Volatile
	private var currentEpubFile: File? = null
	private var book: EpubBook? = null

	@Volatile
	private var zipFile: ZipFile? = null
	private var pagesSnapshot: List<ReaderPage> = emptyList()
	private var canGoPrev = false
	private var canGoNext = false
	private var pendingSearchQuery: String? = null
	private var pagedTopInset = 0
	private var pagedBottomInset = 0
	private var scrollTopInset = 0

	// the neighbouring chapters, preloaded so a boundary crossing is instant. Each owns its own open
	// zip; after a seamless commit the chapter we leave becomes the opposite neighbour (its zip stays
	// open because its block is still in the DOM until it is trimmed).
	private var preloadNextJob: Job? = null
	private var preloadPrevJob: Job? = null

	@Volatile
	private var preloadedNext: PreloadedChapter? = null

	@Volatile
	private var preloadedPrev: PreloadedChapter? = null

	// current chapter document (href -> injected html), served by shouldInterceptRequest so the
	// WebView navigates to a real https url - loadDataWithBaseURL leaves an empty data: history
	// entry behind that blows up with ERR_INVALID_RESPONSE on renavigation
	@Volatile
	private var mainDocument: Pair<String, String>? = null

	@Volatile
	private var progressPm = 0 // reading position inside the chapter, permille

	@Volatile
	private var pendingPm = 0

	// when scrolling backwards into the previous chapter via a full reload, land at its end
	private var landAtEnd = false

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentReaderEpubBinding.inflate(inflater, container, false)

	@SuppressLint("SetJavaScriptEnabled")
	override fun onViewBindingCreated(binding: FragmentReaderEpubBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		with(binding.webView) {
			settings.javaScriptEnabled = true
			settings.allowFileAccess = false
			settings.allowContentAccess = false
			settings.defaultTextEncodingName = "UTF-8"
			// keep the view out of the decor's touchables so the reader tap grid keeps working
			isClickable = false
			isLongClickable = false
			overScrollMode = View.OVER_SCROLL_NEVER
			webViewClient = EpubWebViewClient()
			addJavascriptInterface(Bridge(), "EpubBridge")
		}
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_THEME) { epubTheme }
			.observe(viewLifecycleOwner) { applyColors() }
		viewModel.uiState.observe(viewLifecycleOwner) { state ->
			canGoPrev = state != null && state.hasPreviousChapter()
			canGoNext = state != null && state.hasNextChapter()
			viewBinding?.webView?.evaluateJavascript(
				"if(window.__epub){__epub.setNav($canGoPrev,$canGoNext);}",
				null,
			)
		}
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_SIZE) { epubFontSize }
			.observe(viewLifecycleOwner) { applyTypography() }
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_FAMILY) { epubFontFamily }
			.observe(viewLifecycleOwner) { applyTypography() }
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_LINE_HEIGHT) { epubLineHeight }
			.observe(viewLifecycleOwner) { applyTypography() }
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_HORIZONTAL_PADDING) { epubHorizontalPadding }
			.observe(viewLifecycleOwner) { applyTypography() }
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_TEXT_ALIGN) { epubTextAlign }
			.observe(viewLifecycleOwner) { applyTypography() }
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_READING_MODE) { epubReadingMode }
			.observe(viewLifecycleOwner) {
				viewBinding?.root?.requestApplyInsets()
				applyTypography()
			}
		this.settings.observeAsFlow(AppSettings.KEY_EPUB_PUBLISHER_STYLE) { isEpubPublisherStyleEnabled }
			.observe(viewLifecycleOwner) { applyTypography() }
	}

	override fun onDestroyView() {
		preloadNextJob?.cancel()
		preloadPrevJob?.cancel()
		preloadedNext?.zip?.close()
		preloadedPrev?.zip?.close()
		preloadedNext = null
		preloadedPrev = null
		viewBinding?.webView?.destroy()
		zipFile?.close()
		zipFile = null
		currentEpubFile = null
		super.onDestroyView()
	}

	override fun onCreateAdapter(): BaseReaderAdapter<*>? = null

	private fun barHeight(id: Int): Int {
		val bar = activity?.findViewById<View>(id) ?: return 0
		val margin = (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
		return bar.height + margin
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		pagedTopInset = maxOf(pagedTopInset, barHeight(R.id.appbar_top), bars.top)
		pagedBottomInset = maxOf(pagedBottomInset, barHeight(R.id.toolbar_docked), bars.bottom)
		scrollTopInset = maxOf(scrollTopInset, barHeight(R.id.infoBar))
		val initialLeft = if (isPagedMode) bars.left else 0
		val initialTop = if (isPagedMode) pagedTopInset else scrollTopInset
		val initialRight = if (isPagedMode) bars.right else 0
		val initialBottom = if (isPagedMode) pagedBottomInset else 0
		var viewportChanged = v.paddingLeft != initialLeft || v.paddingTop != initialTop ||
			v.paddingRight != initialRight || v.paddingBottom != initialBottom
		viewBinding?.root?.updatePadding(
			left = initialLeft,
			top = initialTop,
			right = initialRight,
			bottom = initialBottom,
		)
		v.post {
			pagedTopInset = maxOf(pagedTopInset, barHeight(R.id.appbar_top))
			pagedBottomInset = maxOf(pagedBottomInset, barHeight(R.id.toolbar_docked))
			scrollTopInset = maxOf(scrollTopInset, barHeight(R.id.infoBar))
			val left = if (isPagedMode) bars.left else 0
			val top = if (isPagedMode) pagedTopInset else scrollTopInset
			val right = if (isPagedMode) bars.right else 0
			val bottom = if (isPagedMode) pagedBottomInset else 0
			viewportChanged = viewportChanged || v.paddingLeft != left || v.paddingTop != top ||
				v.paddingRight != right || v.paddingBottom != bottom
			v.updatePadding(left = left, top = top, right = right, bottom = bottom)
			if (viewportChanged) applyTypography()
		}
		return insets
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {
		pagesSnapshot = pages
		val target = when {
			pendingState != null -> pages.find { it.chapterId == pendingState.chapterId }
				?.let { it to pendingState.scroll.coerceIn(0, 1000) }

			else -> pages.find { it.chapterId == currentChapterId }?.let { it to progressPm }
		} ?: pages.firstOrNull()?.let { it to 0 } ?: return
		var (page, pm) = target
		if (landAtEnd) {
			landAtEnd = false
			if (page.chapterId != currentChapterId && pm == 0) {
				pm = 1000
			}
		}
		if (page.chapterId == currentChapterId) {
			if (pendingState != null && pm != progressPm) {
				viewBinding?.webView?.evaluateJavascript("if(window.__epub){__epub.restore($pm);}", null)
			}
			return
		}
		loadChapter(page, pm)
	}

	private suspend fun loadChapter(page: ReaderPage, pm: Int) {
		val uri = page.url.toUri()
		val file = File(uri.schemeSpecificPart)
		val href = uri.fragment.orEmpty()
		val html = withContext(Dispatchers.IO) {
			if (file != currentEpubFile) {
				book = EpubParser.parse(file)
				zipFile?.close()
				zipFile = ZipFile(file)
				currentEpubFile = file
			}
			if (href == LocalMangaParser.TOC_ENTRY) {
				buildTocHtml(book)
			} else {
				EpubParser.readEntryText(file, href) ?: "<p>${getString(R.string.not_found_404)}</p>"
			}
		}
		currentChapterId = page.chapterId
		currentHref = href
		progressPm = pm
		pendingPm = pm
		val binding = viewBinding ?: return
		// this is a fresh document: the spliced-in neighbours and their markers are gone, so drop any
		// preloads (they belonged to the previous document) before priming the new ones
		resetPreloads()
		mainDocument = href to injectHtml(html)
		binding.webView.loadUrl("https://$EPUB_HOST/${encodePath(href)}")
		val index = pagesSnapshot.indexOfFirst { it.chapterId == page.chapterId }
		if (index >= 0) {
			viewModel.onCurrentPageChanged(index, index)
		}
		preloadNextChapter()
		preloadPrevChapter()
	}

	private fun resetPreloads() {
		preloadNextJob?.cancel()
		preloadPrevJob?.cancel()
		preloadedNext?.zip?.close()
		preloadedPrev?.zip?.close()
		preloadedNext = null
		preloadedPrev = null
	}

	private fun chapterAt(offset: Int): MangaChapter? {
		val chapters = viewModel.getMangaOrNull()?.chapters ?: return null
		val index = chapters.indexOfFirst { it.id == currentChapterId }
		if (index < 0) return null
		return chapters.getOrNull(index + offset)
	}

	// completion validates against the (possibly already advanced) currentChapterId, so a preload that
	// finishes after the user has moved on is discarded instead of corrupting the window
	private fun preloadNextChapter() {
		val next = chapterAt(1) ?: return
		if (preloadedNext?.chapterId == next.id) {
			attachPreloadedNext()
			return
		}
		if (preloadNextJob?.isActive == true) return
		preloadNextJob = viewLifecycleOwner.lifecycleScope.launch {
			val loaded = loadPreloaded(next) ?: return@launch
			if (chapterAt(1)?.id != loaded.chapterId || preloadedNext?.chapterId == loaded.chapterId) {
				loaded.zip.close()
				return@launch
			}
			preloadedNext?.zip?.close()
			preloadedNext = loaded
			attachPreloadedNext()
		}
	}

	private fun preloadPrevChapter() {
		if (!isPagedMode) return // scroll mode goes back via a clean reload, not a spliced-in prev chapter
		val prev = chapterAt(-1) ?: return
		if (preloadedPrev?.chapterId == prev.id) {
			attachPreloadedPrev()
			return
		}
		if (preloadPrevJob?.isActive == true) return
		preloadPrevJob = viewLifecycleOwner.lifecycleScope.launch {
			val loaded = loadPreloaded(prev) ?: return@launch
			if (chapterAt(-1)?.id != loaded.chapterId || preloadedPrev?.chapterId == loaded.chapterId) {
				loaded.zip.close()
				return@launch
			}
			preloadedPrev?.zip?.close()
			preloadedPrev = loaded
			attachPreloadedPrev()
		}
	}

	private suspend fun loadPreloaded(chapter: MangaChapter): PreloadedChapter? = withContext(Dispatchers.IO) {
		val uri = chapter.url.toUri()
		val file = File(uri.schemeSpecificPart)
		val href = uri.fragment.orEmpty()
		val html = if (href == LocalMangaParser.TOC_ENTRY) {
			buildTocHtml(EpubParser.parse(file))
		} else {
			EpubParser.readEntryText(file, href)
		} ?: return@withContext null
		PreloadedChapter(chapter.id, file, href, html, ZipFile(file))
	}

	private fun attachPreloadedNext() {
		val loaded = preloadedNext ?: return
		val base = "https://$EPUB_HOST/${encodePath(loaded.href)}"
		viewBinding?.webView?.evaluateJavascript("if(window.__epub){__epub.preloadNext(${JSONObject.quote(base)});}", null)
	}

	private fun attachPreloadedPrev() {
		val loaded = preloadedPrev ?: return
		val base = "https://$EPUB_HOST/${encodePath(loaded.href)}"
		viewBinding?.webView?.evaluateJavascript("if(window.__epub){__epub.preloadPrev(${JSONObject.quote(base)});}", null)
	}

	override fun getCurrentState(): ReaderState? = currentChapterId.takeIf { it != 0L }?.let {
		ReaderState(chapterId = it, page = 0, scroll = progressPm)
	}

	override fun switchPageBy(delta: Int) {
		if (isPagedMode) return // paged EPUBs deliberately require a horizontal swipe
		val webView = viewBinding?.webView ?: return
		if (webView.canScrollVertically(delta)) {
			val d = (webView.height * 0.9).toInt() * delta
			webView.evaluateJavascript(
				"if(window.__epub){__epub.el().scrollBy({top:$d,left:0,behavior:${if (isAnimationEnabled()) "'smooth'" else "'auto'"}});}",
				null,
			)
		} else {
			landAtEnd = delta < 0
			viewModel.switchChapterBy(delta)
		}
	}

	// the bottom slider: in paged mode position is a page index (slider has stops), in scroll
	// mode it is chapter progress 0..1000 (a smooth in-chapter scrollbar)
	override fun switchPageTo(position: Int, smooth: Boolean) {
		if (isPagedMode) {
			viewBinding?.webView?.evaluateJavascript("if(window.__epub){__epub.goto($position);}", null)
		} else {
			val pm = position.coerceIn(0, 1000)
			progressPm = pm
			viewBinding?.webView?.evaluateJavascript("if(window.__epub){__epub.restore($pm);}", null)
		}
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		if (isPagedMode) return false
		val webView = viewBinding?.webView ?: return false
		if (!webView.canScrollVertically(delta)) {
			return false
		}
		webView.evaluateJavascript(
			"if(window.__epub){__epub.el().scrollBy({top:$delta,left:0,behavior:${if (smooth && isAnimationEnabled()) "'smooth'" else "'auto'"}});}",
			null,
		)
		return true
	}

	// zoom is intentionally not supported for text books - adjust the text size instead
	override fun onZoomIn() = Unit

	override fun onZoomOut() = Unit

	fun showBookSearch() {
		val input = TextInputEditText(requireContext()).apply {
			setSingleLine()
		}
		val field = TextInputLayout(requireContext()).apply {
			hint = getString(R.string.epub_search_hint)
			boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
			setStartIconDrawable(R.drawable.ic_search)
			addView(input)
		}
		val container = FrameLayout(requireContext()).apply {
			val margin = (24 * resources.displayMetrics.density).toInt()
			setPadding(margin, 8, margin, 0)
			addView(field, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.epub_search_book)
			.setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.search) { _, _ -> searchBook(input.text.toString().trim()) }
			.show()
	}

	private fun searchBook(query: String) {
		if (query.isEmpty()) return
		Toast.makeText(requireContext(), R.string.loading_, Toast.LENGTH_SHORT).show()
		viewLifecycleOwner.lifecycleScope.launch {
			val chapters = viewModel.getMangaOrNull()?.chapters.orEmpty()
			val results = withContext(Dispatchers.IO) {
				chapters.asSequence().filterNot { it.url.toUri().fragment == LocalMangaParser.TOC_ENTRY }
					.mapNotNull { chapter ->
						val uri = chapter.url.toUri()
						val text = EpubParser.readEntryText(File(uri.schemeSpecificPart), uri.fragment.orEmpty())
							?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
							?: return@mapNotNull null
						val match = text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return@mapNotNull null
						val start = (match - 45).coerceAtLeast(0)
						val end = (match + query.length + 70).coerceAtMost(text.length)
						SearchResult(chapter.id, chapter.title.orEmpty(), text.substring(start, end).replace(Regex("\\s+"), " ").trim(), match * 1000 / text.length.coerceAtLeast(1))
					}.take(MAX_SEARCH_RESULTS).toList()
			}
			if (results.isEmpty()) {
				Toast.makeText(requireContext(), R.string.epub_no_search_results, Toast.LENGTH_SHORT).show()
				return@launch
			}
			val adapter = object : ArrayAdapter<SearchResult>(requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, results) {
				override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
					val row = super.getView(position, convertView, parent)
					val result = getItem(position) ?: return row
					row.findViewById<TextView>(android.R.id.text1).apply {
						text = result.title.ifEmpty { getString(R.string.epub_untitled_chapter) }
						gravity = Gravity.START or Gravity.CENTER_VERTICAL
					}
					row.findViewById<TextView>(android.R.id.text2).apply {
						text = result.snippet
						gravity = Gravity.START or Gravity.CENTER_VERTICAL
						maxLines = 2
					}
					return row
				}
			}
			val dialog = MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.search_results)
				.setAdapter(adapter) { _, index ->
					val result = results[index]
					pendingSearchQuery = query
					viewModel.switchChapter(result.chapterId, result.progress)
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
			dialog.listView.apply {
				divider = ColorDrawable(requireContext().getThemeColor(materialR.attr.colorOutlineVariant, Color.GRAY))
				dividerHeight = resources.displayMetrics.density.toInt().coerceAtLeast(1)
			}
		}
	}

	private fun applyColors() {
		val binding = viewBinding ?: return
		val bg = resolveBackgroundColor()
		val fg = foregroundFor(bg)
		val accent = requireContext().getThemeColor(appcompatR.attr.colorPrimary, Color.BLUE)
		binding.root.setBackgroundColor(bg)
		binding.webView.setBackgroundColor(bg)
		binding.webView.evaluateJavascript(
			"var s=document.documentElement.style;" +
				"s.setProperty('--ep-bg','${bg.toCssColor()}');" +
				"s.setProperty('--ep-fg','${fg.toCssColor()}');" +
				"s.setProperty('--ep-ac','${accent.toCssColor()}');",
			null,
		)
	}

	// page theme is epub-only (settings.epubTheme), independent from the manga reader background;
	// "system" follows day/night mode, not the reader activity theme (which is always dark)
	private fun resolveBackgroundColor(): Int {
		val dark = when (settings.epubTheme) {
			"light" -> false
			"dark" -> true
			"black" -> return Color.BLACK
			else -> resources.isNightMode
		}
		return if (dark) {
			ContextThemeWrapper(requireContext(), materialR.style.ThemeOverlay_Material3_Dark)
				.getThemeColor(android.R.attr.colorBackground, Color.BLACK)
		} else {
			ContextThemeWrapper(requireContext(), materialR.style.ThemeOverlay_Material3_Light)
				.getThemeColor(android.R.attr.colorBackground, Color.WHITE)
		}
	}

	// the reader activity theme can be dark while the app is in day mode, so pick the text
	// color from the actual background luminance instead of the day/night flag
	private fun foregroundFor(backgroundColor: Int): Int =
		if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) {
			0xFF1B1B1F.toInt()
		} else {
			0xFFE4E4E8.toInt()
		}

	private fun Int.toCssColor(): String = String.format("#%06X", 0xFFFFFF and this)

	private val isPagedMode: Boolean
		get() = settings.epubReadingMode == EPUB_MODE_PAGED

	private fun applyTypography() {
		val webView = viewBinding?.webView ?: return
		val family = settings.epubFontFamily.replace("'", "\\'")
		webView.evaluateJavascript(
			"if(window.__epub){__epub.style('${settings.epubFontSize}%','$family'," +
				"'${settings.epubLineHeight / 100f}',${settings.epubHorizontalPadding}," +
				"'${settings.epubTextAlign}',${settings.isEpubPublisherStyleEnabled},$isPagedMode,$progressPm);}",
			null,
		)
	}

	private fun injectHtml(source: String): String {
		val bgColor = resolveBackgroundColor()
		val bg = bgColor.toCssColor()
		val fg = foregroundFor(bgColor).toCssColor()
		val accent = requireContext().getThemeColor(appcompatR.attr.colorPrimary, Color.BLUE).toCssColor()
		val head = """
			<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
			<style>
			:root{--ep-bg:$bg;--ep-fg:$fg;--ep-ac:$accent;--ep-fs:${settings.epubFontSize}%;
			--ep-font:${settings.epubFontFamily};--ep-lh:${settings.epubLineHeight / 100f};
			--ep-pad:${settings.epubHorizontalPadding}px;--ep-align:${settings.epubTextAlign};}
			html{background:var(--ep-bg) !important;}
			html:not(.ep-publisher) body{background:var(--ep-bg) !important;color:var(--ep-fg) !important;
			font-size:var(--ep-fs) !important;line-height:var(--ep-lh) !important;
			font-family:var(--ep-font) !important;text-align:var(--ep-align) !important;}
			body{margin:0 !important;padding:20px var(--ep-pad) !important;box-sizing:border-box;word-wrap:break-word;}
			html:not(.ep-publisher) body *{color:var(--ep-fg) !important;background-color:transparent !important;
			font-family:var(--ep-font) !important;line-height:var(--ep-lh) !important;}
			html:not(.ep-publisher) p,html:not(.ep-publisher) div,html:not(.ep-publisher) section,
			html:not(.ep-publisher) article,html:not(.ep-publisher) li{text-align:var(--ep-align) !important;
			font-size:inherit !important;}
			html:not(.ep-publisher) span{font-size:inherit !important;}
			html:not(.ep-publisher) h1,html:not(.ep-publisher) h2,html:not(.ep-publisher) h3,
			html:not(.ep-publisher) h4,html:not(.ep-publisher) h5,html:not(.ep-publisher) h6{text-align:left !important;}
			html:not(.ep-publisher) a,html:not(.ep-publisher) a *{color:var(--ep-ac) !important;}
			html.ep-paged{height:100%;overflow:hidden;touch-action:none;}
			html.ep-paged body{height:100%;overflow:visible;
			column-width:calc(100vw - (var(--ep-pad) * 2));
			column-gap:calc(var(--ep-pad) * 2);column-fill:auto;
			width:auto !important;max-width:none !important;will-change:transform;}
			.__epub_chapter_boundary{height:0;margin:20px 0;border-top:1px solid var(--ep-fg);opacity:.38;clear:both;}
			html.ep-paged .__epub_chapter_boundary{break-before:column;margin:0;border:0;opacity:0;}
			img,svg,image,video{max-width:100% !important;height:auto !important;}
			</style>
		""".trimIndent()
		val script = """
			<script>
			(function(){
			var nav={prev:false,next:false},paged=$isPagedMode,ANIM=260;
			// The document holds up to three chapter blocks side by side: [prev][current][next].
			// Boundary markers separate them. The current chapter's page window is re-derived from the
			// marker positions on every measure, so it is always correct - even after a resize.
			var E={
			 cur:0,pc:1,basePage:0,baseScroll:0,busy:false,prevMarker:null,nextMarker:null,
			 el:function(){return document.scrollingElement||document.documentElement;},
			 stepW:function(){return document.documentElement.clientWidth||window.innerWidth;},
			 total:function(){return Math.max(1,Math.round(document.body.scrollWidth/this.stepW()));},
			 // a marker forced to a column boundary sits at x = column_index * stepW
			 colOf:function(node){return Math.round(node.offsetLeft/this.stepW());},
			 measure:function(){
			  this.basePage=this.prevMarker?this.colOf(this.prevMarker):0;
			  var end=this.nextMarker?this.colOf(this.nextMarker):this.total();
			  this.pc=Math.max(1,end-this.basePage);
			  if(this.cur<this.basePage)this.cur=this.basePage;
			  if(this.cur>this.basePage+this.pc-1)this.cur=this.basePage+this.pc-1;
			  return this.pc;},
			 pageCount:function(){return this.pc;},
			 scrollStart:function(){return this.baseScroll;},
			 scrollEnd:function(){return this.nextMarker?this.nextMarker.offsetTop:this.el().scrollHeight;},
			 apply:function(animate){var b=document.body.style;
			  b.transition=animate?'transform .25s cubic-bezier(.25,.1,.25,1)':'none';
			  b.transform='translate3d(-'+(this.cur*this.stepW())+'px,0,0)';},
			 report:function(){var pm,localPage=0;
			  if(paged){localPage=this.cur-this.basePage;pm=this.pc<=1?1000:Math.min(1000,Math.max(0,Math.round(localPage*1000/(this.pc-1))));}
			  else{var se=this.el(),s=this.scrollStart(),e=this.scrollEnd(),m=Math.max(0,e-s-window.innerHeight),p=Math.max(0,se.scrollTop-s);pm=m<=0?1000:Math.min(1000,Math.round(p*1000/m));}
			  EpubBridge.onProgress(pm,paged?localPage:0,paged?this.pc:0);},
			 restore:function(pm){
			  if(paged){this.cur=this.basePage+Math.max(0,Math.min(this.pc-1,Math.round(pm/1000*(this.pc-1))));this.apply(false);}
			  else{var se=this.el(),s=this.scrollStart(),e=this.scrollEnd();se.scrollTop=s+pm/1000*Math.max(0,e-s-window.innerHeight);}
			  this.report();},
			 goto:function(p){if(!paged||this.busy)return;this.cur=this.basePage+Math.max(0,Math.min(this.pc-1,p));this.apply(true);this.report();},
			 rewrite:function(doc,base){doc.querySelectorAll('[src],[href],[poster]').forEach(function(el){['src','href','poster'].forEach(function(a){var v=el.getAttribute(a);if(v&&v.charAt(0)!=='#'){try{el.setAttribute(a,new URL(v,base).href);}catch(x){}}});});},
			 boundary:function(){var m=document.createElement('div');m.className='__epub_chapter_boundary';return m;},
			 preloadNext:function(base){if(this.nextMarker)return;
			  fetch('https://$EPUB_HOST$NEXT_DOCUMENT_PATH',{cache:'no-store'}).then(function(r){return r.ok?r.text():Promise.reject();}).then(function(raw){
			   if(E.nextMarker)return;
			   var d=new DOMParser().parseFromString(raw,'text/html');E.rewrite(d,base);
			   var m=E.boundary();document.body.appendChild(m);while(d.body.firstChild)document.body.appendChild(d.body.firstChild);
			   E.nextMarker=m;if(paged)E.measure();E.report();
			  }).catch(function(){});},
			 preloadPrev:function(base){if(!paged||this.prevMarker)return;
			  fetch('https://$EPUB_HOST$PREV_DOCUMENT_PATH',{cache:'no-store'}).then(function(r){return r.ok?r.text():Promise.reject();}).then(function(raw){
			   if(E.prevMarker)return;
			   var d=new DOMParser().parseFromString(raw,'text/html');E.rewrite(d,base);
			   var m=E.boundary();var first=document.body.firstChild;document.body.insertBefore(m,first);
			   var frag=document.createDocumentFragment();while(d.body.firstChild)frag.appendChild(d.body.firstChild);
			   document.body.insertBefore(frag,m);
			   E.prevMarker=m;
			   if(paged){var added=E.colOf(m);E.basePage=added;E.cur+=added;E.measure();E.apply(false);}
			   else{E.el().scrollTop+=m.offsetTop;}
			   E.report();
			  }).catch(function(){});},
			 // slide fully into the neighbour, then swap chapters once the animation lands
			 enterNext:function(){if(this.busy)return;if(!this.nextMarker){if(nav.next)EpubBridge.onEdgeSwipe(1);return;}
			  this.busy=true;this.cur=this.basePage+this.pc;this.apply(true);setTimeout(function(){E.commitNext();},ANIM);},
			 enterPrev:function(){if(this.busy)return;if(!this.prevMarker){if(nav.prev)EpubBridge.onEdgeSwipe(-1);return;}
			  this.busy=true;this.cur=this.basePage-1;this.apply(true);setTimeout(function(){E.commitPrev();},ANIM);},
			 // promote next->current, trim the far (prev) block; compensate so nothing visibly jumps
			 commitNext:function(){
			  if(!this.nextMarker){this.busy=false;return false;}
			  if(!EpubBridge.onSeamlessNext()){this.busy=false;if(paged){this.measure();this.cur=this.basePage+this.pc-1;this.apply(true);}this.report();return false;}
			  if(paged){
			   // trim the far (prev) block so at most [prev][current] remain; compensate so nothing jumps
			   if(this.prevMarker){while(document.body.firstChild&&document.body.firstChild!==this.prevMarker)document.body.removeChild(document.body.firstChild);document.body.removeChild(this.prevMarker);}
			   this.prevMarker=this.nextMarker;this.nextMarker=null;
			   this.measure();this.cur=this.basePage;this.apply(false);
			  }else{
			   // scroll mode keeps the appended content in place and just advances the chapter baseline
			   this.baseScroll=this.nextMarker.offsetTop;this.nextMarker=null;
			  }
			  this.busy=false;this.report();return true;},
			 // promote prev->current, trim the far (next) block
			 commitPrev:function(){
			  if(!this.prevMarker){this.busy=false;return false;}
			  if(!EpubBridge.onSeamlessPrev()){this.busy=false;this.measure();this.cur=this.basePage;this.apply(true);this.report();return false;}
			  if(this.nextMarker){
			   while(document.body.lastChild&&document.body.lastChild!==this.nextMarker)document.body.removeChild(document.body.lastChild);
			   document.body.removeChild(this.nextMarker);}
			  this.nextMarker=this.prevMarker;this.prevMarker=null;
			  if(paged){this.measure();this.cur=Math.min(this.cur,this.basePage+this.pc-1);if(this.cur<this.basePage)this.cur=this.basePage;this.apply(false);}
			  this.busy=false;this.report();return true;},
			 page:function(d){if(!paged||this.busy)return;var n=this.cur+d;
			  if(n<this.basePage){this.enterPrev();return;}
			  if(n>=this.basePage+this.pc){this.enterNext();return;}
			  this.cur=n;this.apply(true);this.report();},
			 style:function(fs,font,lh,pad,align,publisher,isPaged,pm){var s=document.documentElement.style;
			  s.setProperty('--ep-fs',fs);s.setProperty('--ep-font',font);s.setProperty('--ep-lh',lh);
			  s.setProperty('--ep-pad',pad+'px');s.setProperty('--ep-align',align);
			  paged=isPaged;document.documentElement.classList.toggle('ep-publisher',publisher);
			  document.documentElement.classList.toggle('ep-paged',paged);
			  if(!paged){document.body.style.transform='none';document.body.style.transition='none';E.baseScroll=E.prevMarker?E.prevMarker.offsetTop:0;}
			  requestAnimationFrame(function(){if(paged)E.measure();E.restore(pm);});},
			 setNav:function(p,n){nav.prev=p;nav.next=n;}
			};
			document.documentElement.classList.toggle('ep-publisher',${settings.isEpubPublisherStyleEnabled});
			document.documentElement.classList.toggle('ep-paged',paged);
			window.__epub=E;
			var rt;window.addEventListener('resize',function(){clearTimeout(rt);rt=setTimeout(function(){if(paged){E.measure();E.apply(false);}E.report();},120);});
			// scroll mode is seamless forward only: when the boundary reaches mid-viewport, promote the
			// appended next chapter to current. Backward navigation is a clean reload (onEdgeSwipe).
			var reporting=false;window.addEventListener('scroll',function(){if(paged)return;if(!reporting){reporting=true;requestAnimationFrame(function(){reporting=false;
			 if(E.nextMarker&&E.el().scrollTop+window.innerHeight/2>=E.nextMarker.offsetTop)E.commitNext();else E.report();});}},{passive:true});
			// scroll mode: at the true document edge (no neighbour spliced yet) fall back to a reload
			function atBottom(){var se=E.el();return se.scrollTop+window.innerHeight>=se.scrollHeight-2;}
			function atTop(){return E.el().scrollTop<=2;}
			var startX=null,startY=null,fired=false,multi=false,dragging=false,dragDx=0;
			document.addEventListener('touchstart',function(e){
			 if(e.touches.length>1){multi=true;startX=null;startY=null;dragging=false;return;}
			 startX=e.touches[0].clientX;startY=e.touches[0].clientY;fired=false;multi=false;dragging=false;dragDx=0;},{passive:true});
			document.addEventListener('touchmove',function(e){
			 if(e.touches.length>1){multi=true;startX=null;startY=null;dragging=false;return;}
			 if(startY===null||fired||multi)return;
			 var dx=e.touches[0].clientX-startX,dy=e.touches[0].clientY-startY;
			 if(paged){
			  if(E.busy)return;
			  // follow the finger, snap on release - a half-recognized swipe can't strand the page
			  if(!dragging&&Math.abs(dx)>10&&Math.abs(dx)>Math.abs(dy))dragging=true;
			  if(dragging){dragDx=dx;var off=dx;
			   var atStart=E.cur<=E.basePage,atEnd=E.cur>=E.basePage+E.pc-1;
			   // resist only when there is genuinely nothing to slide into (no spliced neighbour)
			   if((atStart&&dx>0&&!E.prevMarker)||(atEnd&&dx<0&&!E.nextMarker))off=dx/3;
			   var b=document.body.style;b.transition='none';
			   b.transform='translate3d('+(-(E.cur*E.stepW()-off))+'px,0,0)';}
			  return;
			 }
			 if(dy<-8&&atBottom()&&nav.next&&!E.nextMarker){fired=true;EpubBridge.onEdgeSwipe(1);}
			 else if(dy>8&&atTop()&&nav.prev&&!E.prevMarker){fired=true;EpubBridge.onEdgeSwipe(-1);}
			},{passive:true});
			document.addEventListener('touchend',function(e){
			 if(multi){if(e.touches.length===0)multi=false;startX=null;startY=null;dragging=false;return;}
			 if(startX===null)return;
			 if(paged&&dragging&&!E.busy){
			  var w=E.stepW();
			  if(dragDx<=-w*0.2){
			   if(E.cur<E.basePage+E.pc-1){E.cur++;E.apply(true);E.report();}
			   else if(E.nextMarker){E.enterNext();}
			   else if(nav.next){EpubBridge.onEdgeSwipe(1);}else{E.apply(true);}
			  }else if(dragDx>=w*0.2){
			   if(E.cur>E.basePage){E.cur--;E.apply(true);E.report();}
			   else if(E.prevMarker){E.enterPrev();}
			   else if(nav.prev){EpubBridge.onEdgeSwipe(-1);}else{E.apply(true);}
			  }else{E.apply(true);}
			 }
			 startX=null;startY=null;dragging=false;dragDx=0;
			},{passive:true});
			// measure page count once, after fonts have loaded so column widths are final
			function boot(){if(paged)E.measure();EpubBridge.onReady();}
			window.addEventListener('load',function(){
			 if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){requestAnimationFrame(boot);});}
			 else{requestAnimationFrame(boot);}
			});
			})();
			</script>
		""".trimIndent()
		val headClose = source.indexOf("</head>", ignoreCase = true)
		val sb = StringBuilder(source.length + head.length + script.length)
		if (headClose >= 0) {
			sb.append(source, 0, headClose).append(head).append(source, headClose, source.length)
		} else {
			sb.append(head).append(source)
		}
		val scriptAnchor = sb.lastIndexOf("</body>")
		if (scriptAnchor >= 0) {
			sb.insert(scriptAnchor, script)
		} else {
			sb.append(script)
		}
		return sb.toString()
	}

	private fun buildTocHtml(book: EpubBook?): String {
		val sb = StringBuilder("<html><head><title></title></head><body><h2>")
		sb.append(LocalMangaParser.TOC_TITLE.escapeHtml()).append("</h2>")
		book?.toc?.forEach { item ->
			sb.append("<p style=\"margin:0.4em 0 0.4em ")
				.append(item.level * 1.2f)
				.append("em;text-align:left;\"><a href=\"https://")
				.append(EPUB_HOST)
				.append('/')
				.append(encodePath(item.href))
				.append("\">")
				.append(item.title.escapeHtml())
				.append("</a></p>")
		}
		sb.append("</body></html>")
		return sb.toString()
	}

	private fun String.escapeHtml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

	private fun encodePath(path: String): String = android.net.Uri.encode(path, "/")

	private inner class Bridge {

		// called synchronously from the page as the reader slides into the appended next chapter.
		// Promotes the chapter we are leaving to the "prev" slot (its block stays in the DOM), moves
		// current onto the preloaded next, and primes the following chapter.
		@JavascriptInterface
		fun onSeamlessNext(): Boolean {
			val next = preloadedNext ?: return false
			val oldZip = zipFile
			val oldFile = currentEpubFile
			val oldHref = currentHref
			val oldId = currentChapterId
			val oldHtml = mainDocument?.second
			preloadPrevJob?.cancel()
			preloadedPrev?.zip?.close()
			preloadedPrev = if (oldZip != null && oldFile != null && oldId != 0L) {
				PreloadedChapter(oldId, oldFile, oldHref.orEmpty(), oldHtml.orEmpty(), oldZip)
			} else {
				null
			}
			zipFile = next.zip
			currentEpubFile = next.file
			currentHref = next.href
			currentChapterId = next.chapterId
			preloadedNext = null
			mainDocument = null
			progressPm = 0
			pendingPm = 0
			view?.post {
				viewModel.onEpubChapterChanged(next.chapterId, 0)
				preloadNextChapter()
			}
			return true
		}

		@JavascriptInterface
		fun onSeamlessPrev(): Boolean {
			val prev = preloadedPrev ?: return false
			val oldZip = zipFile
			val oldFile = currentEpubFile
			val oldHref = currentHref
			val oldId = currentChapterId
			val oldHtml = mainDocument?.second
			preloadNextJob?.cancel()
			preloadedNext?.zip?.close()
			preloadedNext = if (oldZip != null && oldFile != null && oldId != 0L) {
				PreloadedChapter(oldId, oldFile, oldHref.orEmpty(), oldHtml.orEmpty(), oldZip)
			} else {
				null
			}
			zipFile = prev.zip
			currentEpubFile = prev.file
			currentHref = prev.href
			currentChapterId = prev.chapterId
			preloadedPrev = null
			mainDocument = null
			progressPm = 1000
			pendingPm = 1000
			view?.post {
				viewModel.onEpubChapterChanged(prev.chapterId, 1000)
				preloadPrevChapter()
			}
			return true
		}

		@JavascriptInterface
		fun onProgress(pm: Int, page: Int, pageCount: Int) {
			progressPm = pm.coerceIn(0, 1000)
			view?.post {
				viewModel.onEpubProgressChanged(progressPm, page, pageCount)
			}
		}

		@JavascriptInterface
		fun onReady() {
			view?.post {
				viewBinding?.webView?.evaluateJavascript(
					"if(window.__epub){__epub.setNav($canGoPrev,$canGoNext);__epub.restore($pendingPm);}",
					null,
				)
				attachPreloadedNext()
				attachPreloadedPrev()
				pendingSearchQuery?.let { query ->
					pendingSearchQuery = null
					viewBinding?.webView?.evaluateJavascript("window.find(${JSONObject.quote(query)});", null)
				}
			}
		}

		@JavascriptInterface
		fun onEdgeSwipe(delta: Int) {
			view?.post {
				landAtEnd = delta < 0
				viewModel.switchChapterBy(delta)
			}
		}

	}

	private inner class EpubWebViewClient : WebViewClient() {

		override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
			val url = request.url
			if (url.host != EPUB_HOST) {
				// books are local: block any external resource
				return WebResourceResponse("text/plain", "utf-8", null)
			}
			if (url.path == NEXT_DOCUMENT_PATH) {
				val html = preloadedNext?.html ?: return WebResourceResponse("text/plain", "utf-8", null)
				return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
			}
			if (url.path == PREV_DOCUMENT_PATH) {
				val html = preloadedPrev?.html ?: return WebResourceResponse("text/plain", "utf-8", null)
				return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
			}
			val entryName = url.path.orEmpty().removePrefix("/")
			mainDocument?.let { (href, html) ->
				if (entryName == href) {
					return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
				}
			}
			// resources may belong to the current chapter or to either spliced-in neighbour
			for (zip in listOfNotNull(zipFile, preloadedNext?.zip, preloadedPrev?.zip)) {
				val entry = zip.getEntry(entryName) ?: continue
				return WebResourceResponse(guessMimeType(entryName), null, zip.getInputStream(entry))
			}
			return null
		}

		override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
			val url = request.url
			if (url.host != EPUB_HOST) {
				return true // swallow external links
			}
			val entryName = url.path.orEmpty().removePrefix("/")
			if (entryName == currentHref) {
				return false // in-page anchor
			}
			val targetUrl = currentEpubFile?.toZipUri(entryName)?.toString()
			val chapter = viewModel.getMangaOrNull()?.chapters?.find { it.url == targetUrl }
			if (chapter != null) {
				viewModel.switchChapter(chapter.id, 0)
			}
			return true
		}
	}

	private companion object {

		const val EPUB_HOST = "epub.book"
		const val EPUB_MODE_PAGED = "paged"
		const val NEXT_DOCUMENT_PATH = "/__epub_next__"
		const val PREV_DOCUMENT_PATH = "/__epub_prev__"
		const val MAX_SEARCH_RESULTS = 100
		fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
			"html", "htm", "xhtml", "xml" -> "text/html"
			"css" -> "text/css"
			"js" -> "application/javascript"
			"jpg", "jpeg" -> "image/jpeg"
			"png" -> "image/png"
			"gif" -> "image/gif"
			"webp" -> "image/webp"
			"svg" -> "image/svg+xml"
			"ttf" -> "font/ttf"
			"otf" -> "font/otf"
			"woff" -> "font/woff"
			"woff2" -> "font/woff2"
			else -> "application/octet-stream"
		}
	}

	private data class SearchResult(val chapterId: Long, val title: String, val snippet: String, val progress: Int)
	private data class PreloadedChapter(val chapterId: Long, val file: File, val href: String, val html: String, val zip: ZipFile)
}
