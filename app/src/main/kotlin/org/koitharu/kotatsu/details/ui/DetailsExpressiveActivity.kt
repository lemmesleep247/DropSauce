package org.koitharu.kotatsu.details.ui

import android.app.assist.AssistContent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.sheet.BottomSheetCollapseCallback
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.databinding.ActivityDetailsExpressiveBinding
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.main.ui.owners.BottomSheetOwner
import org.koitharu.kotatsu.parsers.model.ContentRating
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import javax.inject.Inject

/**
 * Brand-new Material 3 Expressive details screen, rendered entirely with Jetpack Compose. It shares
 * [DetailsViewModel] and the [org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet] bottom sheet
 * with the legacy [DetailsActivity], but lays everything out fresh: a frosted cover backdrop, an
 * oversized rounded poster, playful stat pills, and large tactile cards.
 */
@AndroidEntryPoint
class DetailsExpressiveActivity :
	BaseActivity<ActivityDetailsExpressiveBinding>(),
	BottomSheetOwner {

	@Inject lateinit var coil: ImageLoader
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var shortcutManager: AppShortcutManager

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider

	private val topInset = mutableIntStateOf(0)
	private val bottomInset = mutableIntStateOf(0)
	private var isDarkTheme = false

	override val bottomSheet: View?
		get() = viewBinding.containerBottomSheet

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsExpressiveBinding.inflate(layoutInflater))
		WindowCompat.setDecorFitsSystemWindows(window, false)
		isDarkTheme = ColorUtils.calculateLuminance(getThemeColor(android.R.attr.colorBackground)) <= 0.5
		WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDarkTheme
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)

		setupContent()
		setupBottomSheet()
		setupSwipeRefresh()

		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.composeView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)

		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.mangaDetails.observe(this) {
			it?.let { d -> title = d.toManga().title }
			invalidateOptionsMenu()
		}
		viewModel.onActionDone
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.composeView))
		viewModel.onError
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(
				this,
				DetailsErrorObserver(
					activity = this,
					snackbarHost = viewBinding.composeView,
					bottomSheet = viewBinding.containerBottomSheet,
					viewModel = viewModel,
					resolver = exceptionResolver,
				),
			)
		viewModel.onMangaRemoved.observeEvent(this) { finishAfterTransition() }
		viewModel.onDownloadStarted
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.composeView))
		viewModel.chapters.observe(this, PrefetchObserver(this))
		viewModel.coverUrl.observe(this, ::extractAccent)
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> =
		viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	private fun setupContent() {
		val actions = DetailsExpressiveActions(
			onCoverClick = { manga ->
				val url = viewModel.coverUrl.value ?: return@DetailsExpressiveActions
				router.openImage(url = url, source = manga.source, manga = manga)
			},
			onTitleClick = { title -> showTitleDialog(title) },
			onSourceClick = { manga -> router.openList(manga.source, null, null) },
			onLocalClick = { manga -> router.showLocalInfoDialog(manga) },
			onFavoriteClick = { manga -> router.showFavoriteDialog(manga, viewModel.accentColor.value) },
			onAuthorClick = { author ->
				router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source ?: return@DetailsExpressiveActions)
			},
			onTagClick = { tag -> router.showTagDialog(tag) },
			onScrobblingMore = {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return@DetailsExpressiveActions,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			},
			onRelatedMore = { manga -> router.openRelated(manga) },
			onRelatedClick = { item -> router.openDetails(item.toMangaWithOverride()) },
		)
		val peekHeightPx = resources.getDimensionPixelSize(R.dimen.details_bs_peek_height)
		viewBinding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.composeView.setContent {
			org.koitharu.kotatsu.settings.compose.DropSauceTheme {
				val density = androidx.compose.ui.platform.LocalDensity.current
				val details by viewModel.mangaDetails.collectAsState()
				val history by viewModel.historyInfo.collectAsState()
				val loading by viewModel.isLoading.collectAsState()
				val favs by viewModel.favouriteCategories.collectAsState()
				val scrob by viewModel.scrobblingInfo.collectAsState()
				val related by viewModel.relatedManga.collectAsState()
				val localSize by viewModel.localSize.collectAsState()
				val srcTitle by viewModel.cachedSourceTitle.collectAsState()
				val accentInt by viewModel.accentColor.collectAsState()
				val coverUrl by viewModel.coverUrl.collectAsState()
				val backdropUrl by viewModel.backdropUrl.collectAsState()
				val favLabel = favs.takeIf { it.isNotEmpty() }?.joinToString { it.title }

				DetailsExpressiveScreen(
					details = details,
					historyInfo = history,
					isLoading = loading,
					favouriteCount = favs.size,
					favouriteLabel = favLabel,
					scrobblings = scrob,
					related = related,
					localSize = localSize,
					sourceTitle = srcTitle,
					accent = accentInt?.let { Color(it) },
					imageLoader = coil,
					coverUrl = coverUrl,
					backdropUrl = backdropUrl,
					isBackdropEnabled = settings.isBackdropEnabled,
					style = settings.detailsUiMode,
					topInset = with(density) { topInset.intValue.toDp() },
					bottomContentPadding = with(density) { peekHeightPx.toDp() } + with(density) { bottomInset.intValue.toDp() },
					onScroll = ::onContentScroll,
					actions = actions,
				)
			}
		}
	}

	private fun setupBottomSheet() {
		val sheet = viewBinding.containerBottomSheet
		onBackPressedDispatcher.addCallback(BottomSheetCollapseCallback(sheet))
		val navbarDim = viewBinding.navbarDim
		BottomSheetBehavior.from(sheet).addBottomSheetCallback(
			object : BottomSheetBehavior.BottomSheetCallback() {
				override fun onStateChanged(bottomSheet: View, newState: Int) {
					// Pull-to-refresh only while the chapters sheet is fully collapsed, so dragging the
					// sheet up never fights the refresh gesture.
					viewBinding.swipeRefreshLayout.isEnabled = newState == BottomSheetBehavior.STATE_COLLAPSED
				}

				override fun onSlide(bottomSheet: View, slideOffset: Float) {
					navbarDim.alpha = 1f - slideOffset.coerceAtLeast(0f)
				}
			},
		)
	}

	private fun setupSwipeRefresh() {
		val swipeRefresh = viewBinding.swipeRefreshLayout
		swipeRefresh.setOnRefreshListener { viewModel.reload() }
		viewModel.isLoading.observe(this) { swipeRefresh.isRefreshing = it }
		// Tint the unified loading indicator with the cover accent when "colors from cover" is on.
		viewModel.accentColor.observe(this) { color ->
			if (color != null) {
				swipeRefresh.setIndicatorColor(color)
			}
		}
	}

	// Drives the top app bar from the Compose scroll position: the bar fades from transparent
	// (over the backdrop) to the theme surface, and the manga title appears once it scrolls past.
	private fun onContentScroll(scrollY: Int) {
		val density = resources.displayMetrics.density
		val scrim = (scrollY / (density * SCRIM_THRESHOLD_DP)).coerceIn(0f, 1f)
		viewBinding.appbar.setBackgroundColor(
			ColorUtils.setAlphaComponent(getThemeColor(android.R.attr.colorBackground), (scrim * 255f).toInt()),
		)
		supportActionBar?.setDisplayShowTitleEnabled(scrollY > density * TITLE_THRESHOLD_DP)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		topInset.intValue = bars.top
		bottomInset.intValue = bars.bottom
		viewBinding.appbar.updatePadding(top = bars.top)
		viewBinding.navbarDim.updateLayoutParams { height = bars.bottom }
		return insets
	}

	private fun showTitleDialog(title: String) {
		val text = title.nullIfEmpty() ?: return
		buildAlertDialog(this) {
			setMessage(text)
			setNegativeButton(R.string.close, null)
			setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
				copyToClipboard(getString(R.string.content_type_manga), text)
			}
		}.show()
	}

	private fun extractAccent(coverUrl: String?) {
		// "Colors from cover" is opt-in; when disabled, keep the app theme (null accent).
		if (!settings.isDetailsDynamicColorEnabled) {
			viewModel.accentColor.value = null
			return
		}
		coverUrl ?: return
		val source = viewModel.getMangaOrNull()?.source
		lifecycleScope.launch {
			val color = runCatching {
				withContext(Dispatchers.Default) {
					val request = ImageRequest.Builder(this@DetailsExpressiveActivity)
						.data(coverUrl)
						.allowHardware(false)
						.mangaSourceExtra(source)
						.build()
					val bitmap = coil.execute(request).toBitmapOrNull() ?: return@withContext null
					extractSwatch(bitmap)
				}
			}.getOrNull() ?: return@launch
			viewModel.accentColor.value = color
		}
	}

	private fun extractSwatch(bitmap: Bitmap): Int? {
		val palette = Palette.from(bitmap).maximumColorCount(24).generate()
		val raw = palette.vibrantSwatch?.rgb
			?: palette.lightVibrantSwatch?.rgb
			?: palette.darkVibrantSwatch?.rgb
			?: palette.mutedSwatch?.rgb
			?: palette.dominantSwatch?.rgb
			?: return null
		return harmonizeAccent(raw)
	}

	@ColorInt
	private fun harmonizeAccent(@ColorInt color: Int): Int {
		// Uses the precomputed theme brightness (no off-main-thread theme access during extraction).
		val hsl = FloatArray(3)
		ColorUtils.colorToHSL(color, hsl)
		hsl[1] = hsl[1].coerceIn(0.28f, 0.62f)
		hsl[2] = if (isDarkTheme) hsl[2].coerceIn(0.56f, 0.72f) else hsl[2].coerceIn(0.34f, 0.46f)
		return ColorUtils.HSLToColor(hsl)
	}

	private class PrefetchObserver(
		private val context: android.content.Context,
	) : kotlinx.coroutines.flow.FlowCollector<List<ChapterListItem>?> {
		private var isCalled = false
		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty() || isCalled) return
			isCalled = true
			val item = value.find { it.isCurrent } ?: value.first()
			MangaPrefetchService.prefetchPages(context, item.chapter)
		}
	}

	private companion object {
		// Scroll distance (dp) over which the top bar fades in to the theme surface.
		private const val SCRIM_THRESHOLD_DP = 260f
		// Scroll distance (dp) after which the toolbar title becomes visible.
		private const val TITLE_THRESHOLD_DP = 300f
	}
}
