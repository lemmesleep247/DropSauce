package org.koitharu.kotatsu.reader.ui.epub

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Html
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.toZipUri
import org.koitharu.kotatsu.databinding.FragmentReaderEpubBinding
import org.koitharu.kotatsu.local.data.input.EpubBook
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
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
 */
@AndroidEntryPoint
class EpubReaderFragment : BaseReaderFragment<FragmentReaderEpubBinding>() {

	@Inject
	lateinit var settings: AppSettings

	private var currentChapterId = 0L
	private var currentHref: String? = null
	private var currentEpubFile: File? = null
	private var book: EpubBook? = null
	private var zipFile: ZipFile? = null
	private var pagesSnapshot: List<ReaderPage> = emptyList()
	private var canGoPrev = false
	private var canGoNext = false
	private var pendingSearchQuery: String? = null

	// current chapter document (href -> injected html), served by shouldInterceptRequest so the
	// WebView navigates to a real https url - loadDataWithBaseURL leaves an empty data: history
	// entry behind that blows up with ERR_INVALID_RESPONSE on renavigation
	@Volatile
	private var mainDocument: Pair<String, String>? = null

	@Volatile
	private var progressPm = 0 // reading position inside the chapter, permille

	@Volatile
	private var pendingPm = 0

	// when scrolling backwards into the previous chapter, land at its end instead of the top
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
		viewModel.readerSettingsProducer.observe(viewLifecycleOwner) {
			it.applyBackground(binding.root)
			applyColors(it.background)
		}
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
		viewBinding?.webView?.destroy()
		zipFile?.close()
		zipFile = null
		currentEpubFile = null
		super.onDestroyView()
	}

	override fun onCreateAdapter(): BaseReaderAdapter<*>? = null

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding?.root?.updatePadding(
			left = if (isPagedMode) bars.left else 0,
			top = if (isPagedMode) bars.top else 0,
			right = if (isPagedMode) bars.right else 0,
			bottom = if (isPagedMode) bars.bottom else 0,
		)
		v.post { applyTypography() }
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
		mainDocument = href to injectHtml(html)
		binding.webView.loadUrl("https://$EPUB_HOST/${encodePath(href)}")
		val index = pagesSnapshot.indexOfFirst { it.chapterId == page.chapterId }
		if (index >= 0) {
			viewModel.onCurrentPageChanged(index, index)
		}
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

	// the bottom slider: position is chapter progress 0..1000 (a smooth in-chapter scrollbar)
	override fun switchPageTo(position: Int, smooth: Boolean) {
		val pm = position.coerceIn(0, 1000)
		progressPm = pm
		viewBinding?.webView?.evaluateJavascript("if(window.__epub){__epub.restore($pm);}", null)
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

	override fun onZoomIn() {
		settings.epubFontSize += 10
	}

	override fun onZoomOut() {
		settings.epubFontSize -= 10
	}

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
							?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
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

	private fun applyColors(background: ReaderBackground) {
		val binding = viewBinding ?: return
		val bg = resolveBackgroundColor(background)
		val fg = foregroundFor(bg)
		val accent = requireContext().getThemeColor(appcompatR.attr.colorPrimary, Color.BLUE)
		binding.webView.setBackgroundColor(bg)
		binding.webView.evaluateJavascript(
			"var s=document.documentElement.style;" +
				"s.setProperty('--ep-bg','${bg.toCssColor()}');" +
				"s.setProperty('--ep-fg','${fg.toCssColor()}');" +
				"s.setProperty('--ep-ac','${accent.toCssColor()}');",
			null,
		)
	}

	private fun resolveBackgroundColor(background: ReaderBackground): Int = when (background) {
		ReaderBackground.DEFAULT -> requireContext().getThemeColor(android.R.attr.colorBackground, Color.WHITE)
		ReaderBackground.LIGHT -> ContextThemeWrapper(requireContext(), materialR.style.ThemeOverlay_Material3_Light)
			.getThemeColor(android.R.attr.colorBackground, Color.WHITE)

		ReaderBackground.DARK -> ContextThemeWrapper(requireContext(), materialR.style.ThemeOverlay_Material3_Dark)
			.getThemeColor(android.R.attr.colorBackground, Color.BLACK)

		ReaderBackground.WHITE -> Color.WHITE
		ReaderBackground.BLACK -> Color.BLACK
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
		val background = viewModel.readerSettingsProducer.value.background
		val bgColor = resolveBackgroundColor(background)
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
			html.ep-paged,html.ep-paged body{height:100%;overflow-y:hidden;}
			html.ep-paged body{column-width:calc(100vw - (var(--ep-pad) * 2));
			column-gap:calc(var(--ep-pad) * 2);column-fill:auto;overflow-x:visible;}
			img,svg,image,video{max-width:100% !important;height:auto !important;}
			</style>
		""".trimIndent()
		val script = """
			<script>
			(function(){
			var nav={prev:false,next:false},paged=$isPagedMode;
			var E={
			 el:function(){return document.scrollingElement||document.documentElement;},
			 max:function(){var se=this.el();return paged?se.scrollWidth-window.innerWidth:se.scrollHeight-window.innerHeight;},
			 pos:function(){var se=this.el();return paged?se.scrollLeft:se.scrollTop;},
			 report:function(){var m=this.max();var pm=m<=0?1000:Math.min(1000,Math.round(this.pos()*1000/m));EpubBridge.onProgress(pm);},
			 restore:function(pm){var se=this.el(),p=pm/1000*this.max();if(paged)se.scrollLeft=p;else se.scrollTop=p;this.report();},
			 page:function(d){var se=this.el(),p=this.pos(),m=this.max();
			  if((d<0&&p<=2)||(d>0&&p>=m-2)){if((d<0&&nav.prev)||(d>0&&nav.next))EpubBridge.onEdgeSwipe(d);return;}
			  se.scrollTo({left:p+d*window.innerWidth,top:0,behavior:'smooth'});},
			 style:function(fs,font,lh,pad,align,publisher,isPaged,pm){var s=document.documentElement.style;
			  s.setProperty('--ep-fs',fs);s.setProperty('--ep-font',font);s.setProperty('--ep-lh',lh);
			  s.setProperty('--ep-pad',pad+'px');s.setProperty('--ep-align',align);
			  paged=isPaged;document.documentElement.classList.toggle('ep-publisher',publisher);
			  document.documentElement.classList.toggle('ep-paged',paged);
			  requestAnimationFrame(function(){E.restore(pm);});},
			 setNav:function(p,n){nav.prev=p;nav.next=n;}
			};
			document.documentElement.classList.toggle('ep-publisher',${settings.isEpubPublisherStyleEnabled});
			document.documentElement.classList.toggle('ep-paged',paged);
			window.__epub=E;
			var reporting=false;window.addEventListener('scroll',function(){if(!reporting){reporting=true;requestAnimationFrame(function(){reporting=false;E.report();});}},{passive:true});
			// keep scrolling past the chapter edge -> seamlessly continue into the next/prev chapter
			function atBottom(){var se=E.el();return se.scrollTop+window.innerHeight>=se.scrollHeight-2;}
			function atTop(){return E.el().scrollTop<=2;}
			var startX=null,startY=null,fired=false;
			document.addEventListener('touchstart',function(e){startX=e.touches[0].clientX;startY=e.touches[0].clientY;fired=false;},{passive:true});
			document.addEventListener('touchmove',function(e){
			 if(startY===null||fired)return;
			 var dx=e.touches[0].clientX-startX,dy=e.touches[0].clientY-startY;
			 if(paged&&Math.abs(dx)>Math.abs(dy)){e.preventDefault();return;}
			 if(dy<-80&&atBottom()&&nav.next){fired=true;EpubBridge.onEdgeSwipe(1);}
			 else if(dy>80&&atTop()&&nav.prev){fired=true;EpubBridge.onEdgeSwipe(-1);}
			},{passive:false});
			document.addEventListener('touchend',function(e){
			 if(paged&&startX!==null&&!fired){var dx=e.changedTouches[0].clientX-startX,dy=e.changedTouches[0].clientY-startY;
			  if(Math.abs(dx)>=60&&Math.abs(dx)>Math.abs(dy)*1.2){fired=true;E.page(dx<0?1:-1);}}
			 startX=null;startY=null;
			},{passive:true});
			window.addEventListener('load',function(){setTimeout(function(){EpubBridge.onReady();},50);});
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

		@JavascriptInterface
		fun onProgress(pm: Int) {
			progressPm = pm.coerceIn(0, 1000)
			view?.post {
				viewModel.onEpubProgressChanged(progressPm)
			}
		}

		@JavascriptInterface
		fun onReady() {
			view?.post {
				viewBinding?.webView?.evaluateJavascript(
					"if(window.__epub){__epub.setNav($canGoPrev,$canGoNext);__epub.restore($pendingPm);}",
					null,
				)
				pendingSearchQuery?.let { query ->
					pendingSearchQuery = null
					viewBinding?.webView?.evaluateJavascript("window.find(${org.json.JSONObject.quote(query)});", null)
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
			val entryName = url.path.orEmpty().removePrefix("/")
			mainDocument?.let { (href, html) ->
				if (entryName == href) {
					return WebResourceResponse("text/html", "utf-8", html.byteInputStream())
				}
			}
			val zip = zipFile ?: return null
			val entry = zip.getEntry(entryName) ?: return null
			return WebResourceResponse(guessMimeType(entryName), null, zip.getInputStream(entry))
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
}
