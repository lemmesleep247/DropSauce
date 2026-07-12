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
import org.koitharu.kotatsu.local.data.input.EpubParser
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
 * One chapter lives in the WebView at a time. Paged mode flows it into CSS columns and turns pages
 * with a transform; scroll mode scrolls it vertically. Reaching a chapter edge simply loads the
 * neighbouring chapter (switchChapterBy) - there is deliberately no cross-chapter splicing, so
 * nothing accumulates in memory and there is no position to get out of sync.
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

	@Volatile
	private var zipFile: ZipFile? = null
	private var pagesSnapshot: List<ReaderPage> = emptyList()
	private var canGoPrev = false
	private var canGoNext = false
	private var pendingSearchQuery: String? = null
	private var pagedTopInset = 0
	private var pagedBottomInset = 0
	private var scrollTopInset = 0

	// current chapter document (href -> injected html), served by shouldInterceptRequest so the
	// WebView navigates to a real https url - loadDataWithBaseURL leaves an empty data: history
	// entry behind that blows up with ERR_INVALID_RESPONSE on renavigation
	@Volatile
	private var mainDocument: Pair<String, String>? = null

	@Volatile
	private var progressPm = 0 // reading position inside the chapter, permille

	@Volatile
	private var pendingPm = 0

	// when moving to the previous chapter, land at its end instead of its start
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
				zipFile?.close()
				zipFile = ZipFile(file)
				currentEpubFile = file
			}
			EpubParser.readEntryText(file, href) ?: "<p>${getString(R.string.not_found_404)}</p>"
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
				chapters.asSequence()
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
			img,svg,image,video{max-width:100% !important;height:auto !important;}
			</style>
		""".trimIndent()
		val script = """
			<script>
			(function(){
			var nav={prev:false,next:false},paged=$isPagedMode;
			// One chapter only. Paged: cur page in [0,totalPages); turn = translate by one viewport width.
			// Reaching an edge just asks the app to load the neighbouring chapter (onEdgeSwipe).
			var E={
			 paged:paged,page:0,totalPages:1,
			 el:function(){return document.scrollingElement||document.documentElement;},
			 stepW:function(){return document.documentElement.clientWidth||window.innerWidth;},
			 measure:function(){this.totalPages=Math.max(1,Math.round(document.body.scrollWidth/this.stepW()));
			  if(this.page>this.totalPages-1)this.page=this.totalPages-1;if(this.page<0)this.page=0;return this.totalPages;},
			 apply:function(animate){var b=document.body.style;
			  b.transition=animate?'transform .2s ease':'none';
			  b.transform='translate3d(-'+(this.page*this.stepW())+'px,0,0)';},
			 report:function(){var pm;
			  if(paged){pm=this.totalPages<=1?1000:Math.round(this.page*1000/(this.totalPages-1));}
			  else{var se=this.el(),m=se.scrollHeight-window.innerHeight;pm=m<=0?1000:Math.round(se.scrollTop*1000/m);}
			  EpubBridge.onProgress(Math.min(1000,Math.max(0,pm)),paged?this.page:0,paged?this.totalPages:0);},
			 restore:function(pm){
			  if(paged){this.measure();this.page=Math.max(0,Math.min(this.totalPages-1,Math.round(pm/1000*(this.totalPages-1))));this.apply(false);}
			  else{var se=this.el();se.scrollTop=pm/1000*Math.max(0,se.scrollHeight-window.innerHeight);}
			  this.report();},
			 goto:function(p){if(!paged)return;this.page=Math.max(0,Math.min(this.totalPages-1,p));this.apply(true);this.report();},
			 fwd:function(){if(this.page<this.totalPages-1){this.page++;this.apply(true);this.report();}else if(nav.next){EpubBridge.onEdgeSwipe(1);}else{this.apply(true);}},
			 back:function(){if(this.page>0){this.page--;this.apply(true);this.report();}else if(nav.prev){EpubBridge.onEdgeSwipe(-1);}else{this.apply(true);}},
			 style:function(fs,font,lh,pad,align,publisher,isPaged,pm){var s=document.documentElement.style;
			  s.setProperty('--ep-fs',fs);s.setProperty('--ep-font',font);s.setProperty('--ep-lh',lh);
			  s.setProperty('--ep-pad',pad+'px');s.setProperty('--ep-align',align);
			  paged=isPaged;this.paged=isPaged;document.documentElement.classList.toggle('ep-publisher',publisher);
			  document.documentElement.classList.toggle('ep-paged',paged);
			  if(!paged){document.body.style.transform='none';document.body.style.transition='none';}
			  requestAnimationFrame(function(){E.restore(pm);});},
			 setNav:function(p,n){nav.prev=p;nav.next=n;}
			};
			document.documentElement.classList.toggle('ep-publisher',${settings.isEpubPublisherStyleEnabled});
			document.documentElement.classList.toggle('ep-paged',paged);
			window.__epub=E;
			var rt;window.addEventListener('resize',function(){clearTimeout(rt);rt=setTimeout(function(){if(paged){E.measure();E.apply(false);}E.report();},120);});
			var reporting=false;window.addEventListener('scroll',function(){if(paged)return;if(!reporting){reporting=true;requestAnimationFrame(function(){reporting=false;E.report();});}},{passive:true});
			// scroll mode: keep dragging past the chapter edge -> load the adjacent chapter
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
			  // follow the finger, snap on release - a half-recognized swipe can't strand the page
			  if(!dragging&&Math.abs(dx)>10&&Math.abs(dx)>Math.abs(dy))dragging=true;
			  if(dragging){dragDx=dx;var off=dx;
			   // resist at the book's true ends (no adjacent chapter)
			   if((E.page<=0&&dx>0&&!nav.prev)||(E.page>=E.totalPages-1&&dx<0&&!nav.next))off=dx/3;
			   var b=document.body.style;b.transition='none';
			   b.transform='translate3d('+(-(E.page*E.stepW()-off))+'px,0,0)';}
			  return;
			 }
			 if(dy<-8&&atBottom()&&nav.next){fired=true;EpubBridge.onEdgeSwipe(1);}
			 else if(dy>8&&atTop()&&nav.prev){fired=true;EpubBridge.onEdgeSwipe(-1);}
			},{passive:true});
			document.addEventListener('touchend',function(e){
			 if(multi){if(e.touches.length===0)multi=false;startX=null;startY=null;dragging=false;return;}
			 if(startX===null)return;
			 if(paged&&dragging){var w=E.stepW();
			  if(dragDx<=-w*0.2)E.fwd();
			  else if(dragDx>=w*0.2)E.back();
			  else E.apply(true);}
			 startX=null;startY=null;dragging=false;dragDx=0;
			},{passive:true});
			// first layout: after fonts load so column widths are final
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

	private fun encodePath(path: String): String = android.net.Uri.encode(path, "/")

	private inner class Bridge {

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
