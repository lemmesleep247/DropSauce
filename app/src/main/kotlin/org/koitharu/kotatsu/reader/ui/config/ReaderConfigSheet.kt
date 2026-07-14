package org.koitharu.kotatsu.reader.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import android.content.res.Configuration
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.databinding.SheetReaderConfigBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject
import kotlin.math.roundToInt
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.local.data.isEpub
import androidx.compose.ui.BiasAlignment
import androidx.compose.animation.core.animateFloatAsState

@AndroidEntryPoint
class ReaderConfigSheet : BaseAdaptiveSheet<SheetReaderConfigBinding>() {

    private val viewModel by activityViewModels<ReaderViewModel>()

    @Inject
    lateinit var orientationHelper: ScreenOrientationHelper

    @Inject
    lateinit var mangaRepositoryFactory: MangaRepository.Factory

    @Inject
    lateinit var pageLoader: PageLoader

    @Inject
    lateinit var coil: ImageLoader

    private lateinit var mode: ReaderMode
    private lateinit var imageServerDelegate: ImageServerDelegate

    @Inject
    lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getInt(AppRouter.KEY_READER_MODE)
            ?.let { ReaderMode.valueOf(it) }
            ?: ReaderMode.STANDARD
        imageServerDelegate = ImageServerDelegate()
    }

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetReaderConfigBinding {
        return SheetReaderConfigBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(
        binding: SheetReaderConfigBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeView.setContent {
            DropSauceTheme {
                ReaderConfigContent()
            }
        }
        // Expand synchronously, before the enter animation starts — a post{} here caused a second
        // settle (the sheet visibly lifted off and dropped back) because the expanded offset was
        // computed one frame after the animation had already begun.
        expandToContent()
    }

    private fun expandToContent() {
        val b = behavior ?: return
        if (b is AdaptiveSheetBehavior.Bottom) {
            if (isLandscape()) {
                b.isFitToContents = false
                val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (sheet != null) {
                    sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                b.isFitToContents = true
            }
        } else if (b is AdaptiveSheetBehavior.Side) {
            val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: dialog?.findViewById<View>(com.google.android.material.R.id.m3_side_sheet)
            if (sheet != null) {
                val displayWidth = resources.displayMetrics.widthPixels
                sheet.layoutParams.width = (displayWidth * 0.5).toInt()
                sheet.requestLayout()
            }
        }
        b.state = AdaptiveSheetBehavior.STATE_EXPANDED
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        return insets
    }

    @Composable
    private fun ReaderConfigContent() {
        val context = LocalContext.current
        val isEpubBook = remember { viewModel.getMangaOrNull()?.isEpub == true }
        if (isEpubBook) {
            EpubConfigContent()
            return
        }
        var currentMode by remember { mutableStateOf(mode) }

        // Pager State for 2 pages (0: Options, 1: Info)
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()

        // Settings states
        var isDoubleOnLandscape by remember { mutableStateOf(settings.isReaderDoubleOnLandscape) }
        var isDoubleOnFoldable by remember { mutableStateOf(settings.isReaderDoubleOnFoldable) }
        var sensitivity by remember { mutableFloatStateOf(settings.readerDoublePagesSensitivity * 100f) }

        // Image Server states
        var isImageServerAvailable by remember { mutableStateOf(false) }
        var imageServerValue by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            isImageServerAvailable = imageServerDelegate.isAvailable()
            imageServerValue = imageServerDelegate.getValue()
        }

        // StateFlow observations from ViewModel and OrientationHelper flow
        val isBookmarkAdded by viewModel.isBookmarkAdded.collectAsState()
        val isAutoRotationEnabled by orientationHelper.observeAutoOrientation().collectAsState(initial = false)
        val uiState by viewModel.uiState.collectAsState()
        val manga = remember(uiState) { viewModel.getMangaOrNull() }

        val callback = remember { findParentCallback(Callback::class.java) }

        // Capture the nav bar inset once: the reader toggles system bars while the sheet is open,
        // and a live navigationBarsPadding() resize makes the shown sheet jump.
        val navBarPadding = remember {
            dialog?.window?.decorView?.let { decor ->
                ViewCompat.getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            } ?: 0
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp + with(LocalDensity.current) { navBarPadding.toDp() }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Swipeable Pager Content
                // No animateContentSize here: the sheet is fit-to-contents, so animating the body
                // height re-settles the whole sheet while it is opening.
                // The pager is pinned to the Read Mode page's height so swiping to the (otherwise
                // shorter) Tools page never resizes the sheet; the tools grid stretches to fill it.
                val density = LocalDensity.current
                var pagerHeightPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (pagerHeightPx > 0) {
                                Modifier.height(with(density) { pagerHeightPx.toDp() })
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        when (page) {
                            0 -> { // Options Page
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .onSizeChanged { pagerHeightPx = it.height },
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    ReadModeSection(
                                        selectedMode = currentMode,
                                        onModeSelected = { newMode ->
                                            if (newMode != currentMode) {
                                                callback?.onReaderModeChanged(newMode)
                                                currentMode = newMode
                                            }
                                        },
                                    )

                                    DoublePageConfigSection(
                                        isModeStandardOrReversed = currentMode == ReaderMode.STANDARD || currentMode == ReaderMode.REVERSED,
                                        isDoubleOnLandscape = isDoubleOnLandscape,
                                        onDoubleOnLandscapeChange = { enabled ->
                                            settings.isReaderDoubleOnLandscape = enabled
                                            isDoubleOnLandscape = enabled
                                            callback?.onDoubleModeChanged(enabled)
                                        },
                                        isDoubleOnFoldable = isDoubleOnFoldable,
                                        onDoubleOnFoldableChange = { enabled ->
                                            settings.isReaderDoubleOnFoldable = enabled
                                            isDoubleOnFoldable = enabled
                                            callback?.onDoubleModeChanged(settings.isReaderDoubleOnLandscape)
                                        },
                                        sensitivity = sensitivity,
                                        onSensitivityChange = { value ->
                                            settings.readerDoublePagesSensitivity = value / 100f
                                            sensitivity = value
                                        },
                                    )

                                    Spacer(modifier = Modifier.height(84.dp))
                                }
                            }

                            1 -> { // Info & Tools Page
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    if (isImageServerAvailable) {
                                        ImageServerItem(
                                            value = imageServerValue,
                                            onClick = {
                                                viewLifecycleScope.launch {
                                                    if (imageServerDelegate.showDialog(context)) {
                                                        imageServerValue = imageServerDelegate.getValue()
                                                        pageLoader.invalidate(clearCache = true)
                                                        viewModel.switchChapterBy(0)
                                                    }
                                                }
                                            },
                                        )
                                    }

                                    ToolsGridSection(
                                        showPageTools = true,
                                        isAutoRotationEnabled = isAutoRotationEnabled,
                                        isOrientationLocked = orientationHelper.isLocked,
                                        isBookmarkAdded = isBookmarkAdded,
                                        onSaveClick = {
                                            callback?.onSavePageClick()
                                            dismissAllowingStateLoss()
                                        },
                                        onOrientationClick = {
                                            orientationHelper.toggleScreenOrientation()
                                        },
                                        onScrollTimerClick = {
                                            callback?.onScrollTimerClick(false)
                                            dismissAllowingStateLoss()
                                        },
                                        onColorFilterClick = {
                                            val page = viewModel.getCurrentPage()
                                            val manga = viewModel.getMangaOrNull()
                                            if (page != null && manga != null) {
                                                router.openColorFilterConfig(manga, page)
                                            }
                                        },
                                        onBookmarkClick = {
                                            viewModel.toggleBookmark()
                                        },
                                        onSettingsClick = {
                                            router.openReaderSettings()
                                            dismissAllowingStateLoss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    )

                                    Spacer(modifier = Modifier.height(84.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 2. Bottom Floating Capsule Tab Switcher
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                BottomPillTabBar(
                    currentPage = pagerState.currentPage,
                    onTabClick = { index ->
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun BottomPillTabBar(
        currentPage: Int,
        onTabClick: (Int) -> Unit,
        tabs: List<Triple<String, Int, Int>> = listOf(
            Triple("Read Mode", R.drawable.ic_book_page, 0),
            Triple("Tools", R.drawable.ic_grid, 1),
        ),
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .width(280.dp)
                    .height(52.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val targetBias = if (currentPage == 0) -1f else 1f
                    val animatedBias by animateFloatAsState(
                        targetValue = targetBias,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "pill_bias",
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.5f)
                            .padding(4.dp)
                            .align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { (title, iconRes, index) ->
                            val isSelected = currentPage == index
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "pill_tab_content_color",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { onTabClick(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = title,
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ImageServerItem(
        value: String?,
        onClick: () -> Unit,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_images),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.image_server),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = value ?: stringResource(R.string.automatic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    @Composable
    private fun ReadModeSection(
        selectedMode: ReaderMode,
        onModeSelected: (ReaderMode) -> Unit,
    ) {
        val modes = listOf(
            ReaderMode.STANDARD to (R.string.standard to R.drawable.ic_reader_ltr),
            ReaderMode.REVERSED to (R.string.r_to_l to R.drawable.ic_reader_rtl),
            ReaderMode.VERTICAL to (R.string.vertical to R.drawable.ic_reader_vertical),
            ReaderMode.WEBTOON to (R.string.webtoon to R.drawable.ic_reader_webtoon),
        )
        val selectedIndex = modes.indexOfFirst { it.first == selectedMode }.coerceAtLeast(0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
						.height(112.dp)
						.padding(8.dp),
                ) {
                    val targetBias = when (selectedIndex) {
                        0 -> -1f
                        1 -> -1f / 3f
                        2 -> 1f / 3f
                        3 -> 1f
                        else -> -1f
                    }
                    val animatedBias by animateFloatAsState(
                        targetValue = targetBias,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "mode_highlighter",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.25f)
                            .align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
							.clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        modes.forEachIndexed { index, (mode, pair) ->
                            val (labelRes, iconRes) = pair
                            val isSelected = selectedMode == mode
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "segment_fg_$index",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { onModeSelected(mode) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
										modifier = Modifier.size(34.dp),
                                        tint = contentColor,
                                    )
									Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(labelRes),
										style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.reader_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    @Composable
    private fun DoublePageConfigSection(
        isModeStandardOrReversed: Boolean,
        isDoubleOnLandscape: Boolean,
        onDoubleOnLandscapeChange: (Boolean) -> Unit,
        isDoubleOnFoldable: Boolean,
        onDoubleOnFoldableChange: (Boolean) -> Unit,
        sensitivity: Float,
        onSensitivityChange: (Float) -> Unit,
    ) {
        val sectionAlpha = if (isModeStandardOrReversed) 1f else 0.38f

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            shadowElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(sectionAlpha)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Toggle 1: Use two pages in landscape
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = isModeStandardOrReversed) { onDoubleOnLandscapeChange(!isDoubleOnLandscape) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_split_horizontal),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.use_two_pages_landscape),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = isDoubleOnLandscape,
                        onCheckedChange = onDoubleOnLandscapeChange,
                        enabled = isModeStandardOrReversed,
                    )
                }

                // Sub-options
                val subOptionsAlpha = if (isModeStandardOrReversed && isDoubleOnLandscape) 1f else 0.38f

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(subOptionsAlpha),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Toggle 2: Auto double on foldable
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isModeStandardOrReversed && isDoubleOnLandscape) { onDoubleOnFoldableChange(!isDoubleOnFoldable) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.width(38.dp)) // indentation for sub-option
                        Text(
                            text = stringResource(R.string.auto_double_foldable),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = isDoubleOnFoldable,
                            onCheckedChange = onDoubleOnFoldableChange,
                            enabled = isModeStandardOrReversed && isDoubleOnLandscape,
                        )
                    }

                    // Slider: Scroll sensitivity
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(38.dp))
                                Text(
                                    text = stringResource(R.string.two_page_scroll_sensitivity),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = "${sensitivity.roundToInt()}%",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isModeStandardOrReversed && isDoubleOnLandscape) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(38.dp))
                            Slider(
                                value = sensitivity,
                                onValueChange = onSensitivityChange,
                                valueRange = 0f..100f,
                                enabled = isModeStandardOrReversed && isDoubleOnLandscape,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    // EPUB books get a single simple menu: text size slider + automatic scroll & settings
    @Composable
    private fun EpubConfigContent() {
        val callback = remember { findParentCallback(Callback::class.java) }
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()
        // book formatting on -> publisher styles win, so our formatting options are inert: grey
        // out everything except search and the page theme toggle
        val editable = !settings.isEpubPublisherStyleEnabled
        val navBarPadding = remember {
            dialog?.window?.decorView?.let { decor ->
                ViewCompat.getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            } ?: 0
        }
        val density = LocalDensity.current
        val pageContent: @Composable (Int) -> Unit = { page ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 68.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (page == 0) {
                    EpubTextSizeSection(enabled = editable)
                    EpubSliderSection(R.drawable.ic_reader_vertical, stringResource(R.string.epub_line_height), settings.epubLineHeight, 100..240, "%", defaultValue = 160, enabled = editable) { settings.epubLineHeight = it }
                    EpubSliderSection(R.drawable.ic_move_horizontal, stringResource(R.string.epub_horizontal_margin), settings.epubHorizontalPadding, 0..64, " dp", defaultValue = 20, enabled = editable) { settings.epubHorizontalPadding = it }
                } else {
                    EpubReadModeSection()
                    EpubChoiceSection(
                        R.drawable.ic_title,
                        stringResource(R.string.epub_font_family),
                        listOf("serif" to stringResource(R.string.epub_font_serif), "sans-serif" to stringResource(R.string.epub_font_sans), "monospace" to stringResource(R.string.epub_font_mono)),
                        settings.epubFontFamily,
                        enabled = editable,
                        fontOf = {
                            when (it) {
                                "sans-serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                                "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                                else -> androidx.compose.ui.text.font.FontFamily.Serif
                            }
                        },
                    ) { settings.epubFontFamily = it }
                    EpubChoiceSection(
                        R.drawable.ic_reader_ltr,
                        stringResource(R.string.epub_text_alignment),
                        listOf("left" to stringResource(R.string.epub_align_left), "justify" to stringResource(R.string.epub_align_justified)),
                        settings.epubTextAlign,
                        enabled = editable,
                    ) { settings.epubTextAlign = it }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ToolGridCard(R.drawable.ic_search, stringResource(R.string.epub_search_book), onClick = { callback?.onEpubSearchClick(); dismissAllowingStateLoss() }, modifier = Modifier.weight(1f).height(64.dp), iconSize = 22.dp)
                        EpubThemeCard(modifier = Modifier.weight(1f).height(64.dp))
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp + with(density) { navBarPadding.toDp() }),
        ) {
            // both tabs share one pager height, sized to the taller tab's real content
            // (not a guessed constant) so there's no dead space above the floating pill
            var pagerHeight by remember { mutableStateOf(0.dp) }
            SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
                val measureConstraints = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
                val tallestPx = (0 until 2).maxOf { page ->
                    subcompose(page) { pageContent(page) }
                        .maxOf { it.measure(measureConstraints).height }
                }
                pagerHeight = with(density) { tallestPx.toDp() }
                layout(0, 0) {}
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pagerHeight),
                verticalAlignment = Alignment.Top,
            ) { page ->
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    pageContent(page)
                }
            }
            BottomPillTabBar(
                currentPage = pagerState.currentPage,
                onTabClick = { scope.launch { pagerState.animateScrollToPage(it) } },
                tabs = listOf(
                    Triple(stringResource(R.string.epub_text_tab), R.drawable.ic_appearance, 0),
                    Triple(stringResource(R.string.epub_reading_tab), R.drawable.ic_book_page, 1),
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    @Composable
    private fun EpubSliderSection(
        icon: Int,
        title: String,
        value: Int,
        range: IntRange,
        suffix: String,
        defaultValue: Int,
        enabled: Boolean = true,
        onChange: (Int) -> Unit,
    ) {
        var current by remember { mutableIntStateOf(value.coerceIn(range)) }
        EpubSettingCard(
            icon, title, "$current$suffix", enabled = enabled,
            onReset = { current = defaultValue.also(onChange) },
        ) {
			EpubContinuousSlider(
                value = current.toFloat(),
				onValueChange = { value ->
					val rounded = value.roundToInt()
					if (rounded != current) current = rounded.also(onChange)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                enabled = enabled,
            )
        }
    }

	@OptIn(ExperimentalMaterial3Api::class)
	@Composable
	private fun EpubContinuousSlider(
		value: Float,
		onValueChange: (Float) -> Unit,
		valueRange: ClosedFloatingPointRange<Float>,
		enabled: Boolean,
	) {
		Slider(
			value = value,
			onValueChange = onValueChange,
			valueRange = valueRange,
			steps = 0,
			enabled = enabled,
			modifier = Modifier.fillMaxWidth(),
		)
	}

    @Composable
    private fun EpubChoiceSection(
        icon: Int,
        title: String,
        choices: List<Pair<String, String>>,
        selected: String,
        enabled: Boolean = true,
        fontOf: ((String) -> androidx.compose.ui.text.font.FontFamily?)? = null,
        onSelected: (String) -> Unit,
    ) {
        var current by remember { mutableStateOf(selected) }
        EpubSettingCard(icon, title, null, enabled = enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (value, label) ->
                    val selected = current == value
                    val color by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        label = "epub_choice_color",
                    )
                    Surface(
                        onClick = { current = value; onSelected(value) },
                        enabled = enabled,
                        shape = CircleShape,
                        color = color,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text(label, textAlign = TextAlign.Center, fontFamily = fontOf?.invoke(value), modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) }
                }
            }
        }
    }

    // read-mode picker styled like the manga reader's segmented control
    @Composable
    private fun EpubReadModeSection(enabled: Boolean = true) {
        val modes = listOf(
            "scroll" to (R.string.epub_mode_scroll to R.drawable.ic_reader_vertical),
			"paged_ltr" to (R.string.epub_mode_paged_ltr to R.drawable.ic_reader_ltr),
			"paged_rtl" to (R.string.epub_mode_paged_rtl to R.drawable.ic_reader_rtl),
        )
		var current by remember {
			mutableStateOf(if (settings.epubReadingMode == "paged") "paged_ltr" else settings.epubReadingMode)
		}
        val selectedIndex = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .alpha(if (enabled) 1f else 0.38f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
						.height(64.dp)
						.padding(6.dp),
                ) {
                    val animatedBias by animateFloatAsState(
						targetValue = selectedIndex - 1f,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "epub_mode_highlighter",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
							.fillMaxWidth(1f / modes.size)
                            .align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
							.clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        modes.forEach { (value, pair) ->
                            val (labelRes, iconRes) = pair
                            val isSelected = current == value
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "epub_segment_fg_$value",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        enabled = enabled,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { current = value; settings.epubReadingMode = value },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
										modifier = Modifier.size(22.dp),
                                        tint = contentColor,
                                    )
									Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(labelRes),
										style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // cycles the epub page theme: follow system -> light -> dark
    @Composable
    private fun EpubThemeCard(modifier: Modifier = Modifier) {
        var theme by remember { mutableStateOf(settings.epubTheme) }
        val label = when (theme) {
            "light" -> R.string.epub_theme_light
            "dark" -> R.string.epub_theme_dark
            "black" -> R.string.epub_theme_black
            else -> R.string.epub_theme_system
        }
        ToolGridCard(
            icon = R.drawable.ic_appearance,
            label = stringResource(label),
            checked = theme != "system",
            onClick = {
                theme = when (theme) {
                    "system" -> "light"
                    "light" -> "dark"
                    "dark" -> "black"
                    else -> "system"
                }
                settings.epubTheme = theme
            },
            modifier = modifier,
            iconSize = 22.dp,
        )
    }

    @Composable
    private fun EpubSettingCard(
        icon: Int,
        title: String,
        value: String?,
        enabled: Boolean = true,
        onReset: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).alpha(if (enabled) 1f else 0.38f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onReset != null) {
                            IconButton(onClick = onReset, enabled = enabled, modifier = Modifier.size(28.dp)) {
                                Icon(painterResource(R.drawable.ic_revert), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (value != null) Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun EpubTextSizeSection(enabled: Boolean = true) {
        var textSize by remember { mutableIntStateOf(settings.epubFontSize.coerceIn(50, 200)) }
        EpubSettingCard(
            R.drawable.ic_size_large, stringResource(R.string.epub_text_size), "$textSize%", enabled = enabled,
            onReset = { textSize = 100.also { settings.epubFontSize = it } },
        ) {
            EpubContinuousSlider(
                value = textSize.toFloat(),
                onValueChange = { value ->
                    val rounded = value.roundToInt()
                    if (rounded != textSize) {
                        textSize = rounded
                        settings.epubFontSize = rounded
                    }
                },
                valueRange = 50f..200f,
                enabled = enabled,
            )
        }
    }

    @Composable
    private fun ToolsGridSection(
        showPageTools: Boolean,
        isAutoRotationEnabled: Boolean,
        isOrientationLocked: Boolean,
        isBookmarkAdded: Boolean,
        onSaveClick: () -> Unit,
        onOrientationClick: () -> Unit,
        onScrollTimerClick: () -> Unit,
        onColorFilterClick: () -> Unit,
        onBookmarkClick: () -> Unit,
        onSettingsClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        // Determine rotation values
        val rotationTitle = if (isAutoRotationEnabled) {
            R.string.lock_screen_rotation
        } else {
            R.string.rotate_screen
        }
        val rotationIcon = if (isAutoRotationEnabled) {
            R.drawable.ic_screen_rotation_lock
        } else {
            R.drawable.ic_screen_rotation
        }
        val isRotationChecked = isAutoRotationEnabled && isOrientationLocked
        // Rows share the page height equally so this page matches the Read Mode page exactly.
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: Save Page, Rotation (Pills) - not applicable to EPUB text chapters
            if (showPageTools) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToolPillCard(
                        icon = R.drawable.ic_save,
                        label = stringResource(R.string.save_page),
                        onClick = onSaveClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    ToolPillCard(
                        icon = rotationIcon,
                        label = stringResource(rotationTitle),
                        onClick = onOrientationClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            // Row 2: Scroll Timer, Color correction (Squares)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolGridCard(
                    icon = R.drawable.ic_timer,
                    label = stringResource(R.string.automatic_scroll),
                    onClick = onScrollTimerClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                ToolGridCard(
                    icon = R.drawable.ic_appearance,
                    label = stringResource(R.string.color_correction),
                    onClick = onColorFilterClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }

            // Row 3: Settings Card (Wide) | Bookmark Button (Round)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolWideCard(
                    icon = R.drawable.ic_settings,
                    title = stringResource(R.string.settings),
                    subtitle = "Advanced reader options",
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                ToolGridCard(
                    icon = if (isBookmarkAdded) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark,
                    label = null,
                    checked = isBookmarkAdded,
                    onClick = onBookmarkClick,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    shape = CircleShape,
                    iconSize = 42.dp,
                )
            }
        }
    }

    @Composable
    private fun ToolWideCard(
        icon: Int,
        title: String,
        subtitle: String,
        checked: Boolean = false,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val containerColor = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        val iconColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = iconColor,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(-90f),
                    tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun ToolGridCard(
        icon: Int,
        label: String?,
        checked: Boolean = false,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
        iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    ) {
        val containerColor = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        val iconColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onClick,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    tint = iconColor,
                )
                if (label != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun ToolPillCard(
        icon: Int,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(48.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = label,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    interface Callback {

        fun onReaderModeChanged(mode: ReaderMode)

        fun onDoubleModeChanged(isEnabled: Boolean)

        fun onSavePageClick()

        fun onScrollTimerClick(isLongClick: Boolean)

        fun onBookmarkClick()

        fun onEpubSearchClick()
    }
}
