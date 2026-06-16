package org.koitharu.kotatsu.details.ui

import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Navigation/action hooks for the expressive details screen. The activity owns the router
 * and supplies these so the Compose layer stays free of Android navigation plumbing.
 */
class DetailsExpressiveActions(
	val onCoverClick: (Manga) -> Unit,
	val onTitleClick: (String) -> Unit,
	val onSourceClick: (Manga) -> Unit,
	val onLocalClick: (Manga) -> Unit,
	val onFavoriteClick: (Manga) -> Unit,
	val onAuthorClick: (String) -> Unit,
	val onTagClick: (MangaTag) -> Unit,
	val onScrobblingMore: () -> Unit,
	val onRelatedMore: (Manga) -> Unit,
	val onRelatedClick: (MangaListModel) -> Unit,
)

private val SCREEN_PADDING = 20.dp
private val CARD_CORNER = 26.dp
private val COVER_WIDTH = 158.dp
private val COVER_HEIGHT = 236.dp
private val COMPACT_COVER_WIDTH = 120.dp
private val COMPACT_COVER_HEIGHT = 178.dp
private const val TAGS_COLLAPSED_ROWS = 3

@Composable
fun DetailsExpressiveScreen(
	details: MangaDetails?,
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	favouriteCount: Int,
	favouriteLabel: String?,
	scrobblings: List<ScrobblingInfo>,
	related: List<MangaListModel>,
	localSize: Long,
	sourceTitle: String?,
	imageLoader: ImageLoader,
	coverUrl: String?,
	backdropUrl: String?,
	isBackdropEnabled: Boolean,
	style: DetailsUiMode,
	topInset: Dp,
	bottomContentPadding: Dp,
	onScroll: (Int) -> Unit,
	actions: DetailsExpressiveActions,
) {
	val manga = details?.toManga()
	val baseScheme = MaterialTheme.colorScheme
	val typography = MaterialTheme.typography

	MaterialTheme(colorScheme = baseScheme, typography = typography) {
		val scheme = MaterialTheme.colorScheme
		val accentColor = scheme.primary
		val scrollState = rememberScrollState()
		val centered = style != DetailsUiMode.COMPACT

		LaunchedEffect(scrollState) {
			snapshotFlow { scrollState.value }.collect(onScroll)
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(scheme.surface),
		) {
			if (isBackdropEnabled && backdropUrl != null) {
				ExpressiveBackdrop(
					url = backdropUrl,
					manga = manga,
					imageLoader = imageLoader,
					surface = scheme.surface,
				)
			}

			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(bottom = bottomContentPadding),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				// Push the hero clear of the translucent top bar / back button.
			Spacer(Modifier.height(topInset + if (centered) 84.dp else 72.dp))

			if (manga == null) {
				LoadingHero()
			} else {
				val favLabel = favouriteLabel ?: stringResource(R.string.add_to_favourites)
				val isFavourite = favouriteCount > 0
				HeroSection(
					centered = centered,
					manga = manga,
					details = details,
					sourceTitle = sourceTitle,
					accent = accentColor,
					imageLoader = imageLoader,
					coverUrl = coverUrl,
					favouriteLabel = favLabel,
					isFavourite = isFavourite,
					onFavouriteClick = { actions.onFavoriteClick(manga) },
					actions = actions,
				)

				// In the centered style the favourite button is a full-width action below the hero;
				// in compact it lives in the info column beside the cover (added inside HeroSection).
				if (centered) {
					Spacer(Modifier.height(20.dp))
					FavouriteButton(
						label = favLabel,
						isFavourite = isFavourite,
						accent = accentColor,
						onClick = { actions.onFavoriteClick(manga) },
					)
				}

				Spacer(Modifier.height(8.dp))
				ProgressCard(historyInfo = historyInfo, isLoading = isLoading, accent = accentColor)

				DescriptionCard(description = details.description)

				TagsSection(tags = manga.tags, accent = accentColor, onTagClick = actions.onTagClick)

				if (scrobblings.isNotEmpty()) {
					ScrobblingSection(
						items = scrobblings,
						imageLoader = imageLoader,
						accent = accentColor,
						onMore = actions.onScrobblingMore,
					)
				}

				if (related.isNotEmpty()) {
					RelatedSection(
						items = related,
						imageLoader = imageLoader,
						accent = accentColor,
						onMore = { actions.onRelatedMore(manga) },
						onItemClick = actions.onRelatedClick,
					)
				}

				if (localSize > 0L) {
					LocalSizeRow(size = localSize, manga = manga, onClick = actions.onLocalClick)
				}

					Spacer(Modifier.height(28.dp))
				}
			}
		}
	}
}

@Composable
private fun ExpressiveBackdrop(
	url: String,
	manga: Manga?,
	imageLoader: ImageLoader,
	surface: Color,
) {
	val ctx = LocalContext.current
	val request = remember(url, manga?.source) {
		ImageRequest.Builder(ctx)
			.data(url)
			.crossfade(true)
			.mangaSourceExtra(manga?.source)
			.build()
	}
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(480.dp),
	) {
		val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			Modifier.blur(40.dp)
		} else {
			Modifier
		}
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxSize()
				.then(blurMod),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to surface.copy(alpha = 0.30f),
						0.4f to surface.copy(alpha = 0.55f),
						0.78f to surface.copy(alpha = 0.94f),
						1f to surface,
					),
				),
		)
	}
}

@Composable
private fun HeroSection(
	centered: Boolean,
	manga: Manga,
	details: MangaDetails?,
	sourceTitle: String?,
	accent: Color,
	imageLoader: ImageLoader,
	coverUrl: String?,
	favouriteLabel: String,
	isFavourite: Boolean,
	onFavouriteClick: () -> Unit,
	actions: DetailsExpressiveActions,
) {
	val nsfwLabel = when (manga.contentRating) {
		ContentRating.SUGGESTIVE -> "16+"
		ContentRating.ADULT -> "18+"
		else -> null
	}
	if (centered) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			CoverCard(manga, coverUrl, imageLoader, COVER_WIDTH, COVER_HEIGHT, 24.dp, null, actions)
			Spacer(Modifier.height(20.dp))
			HeroTexts(centered = true, manga = manga, accent = accent, actions = actions)
			Spacer(Modifier.height(16.dp))
			StatPills(
				centered = true,
				showContentRating = true,
				manga = manga,
				details = details,
				sourceTitle = sourceTitle,
				accent = accent,
				imageLoader = imageLoader,
				onSourceClick = { actions.onSourceClick(manga) },
			)
		}
	} else {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING),
		) {
			// Compact: the content-rating badge sits on the cover instead of as a pill.
			CoverCard(manga, coverUrl, imageLoader, COMPACT_COVER_WIDTH, COMPACT_COVER_HEIGHT, 20.dp, nsfwLabel, actions)
			Spacer(Modifier.width(16.dp))
			Column(modifier = Modifier.weight(1f)) {
				HeroTexts(centered = false, manga = manga, accent = accent, actions = actions)
				Spacer(Modifier.height(12.dp))
				StatPills(
					centered = false,
					showContentRating = false,
					manga = manga,
					details = details,
					sourceTitle = sourceTitle,
					accent = accent,
					imageLoader = imageLoader,
					onSourceClick = { actions.onSourceClick(manga) },
				)
				Spacer(Modifier.height(14.dp))
				FavouriteButton(
					label = favouriteLabel,
					isFavourite = isFavourite,
					accent = accent,
					onClick = onFavouriteClick,
					horizontalPadding = 0.dp,
				)
			}
		}
	}
}

@Composable
private fun CoverCard(
	manga: Manga,
	coverUrl: String?,
	imageLoader: ImageLoader,
	width: Dp,
	height: Dp,
	corner: Dp,
	nsfwLabel: String?,
	actions: DetailsExpressiveActions,
) {
	val ctx = LocalContext.current
	Surface(
		shape = RoundedCornerShape(corner),
		color = MaterialTheme.colorScheme.surfaceVariant,
		tonalElevation = 4.dp,
		shadowElevation = 16.dp,
		modifier = Modifier
			.width(width)
			.height(height),
	) {
		val coverRequest = remember(coverUrl, manga.source) {
			ImageRequest.Builder(ctx)
				.data(coverUrl)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.build()
		}
		Box(modifier = Modifier.fillMaxSize()) {
			AsyncImage(
				model = coverRequest,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.fillMaxSize()
					.clickable { actions.onCoverClick(manga) },
			)
			if (nsfwLabel != null) {
				Surface(
					shape = RoundedCornerShape(8.dp),
					color = Color.Black.copy(alpha = 0.6f),
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(6.dp),
				) {
					Text(
						text = nsfwLabel,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun HeroTexts(
	centered: Boolean,
	manga: Manga,
	accent: Color,
	actions: DetailsExpressiveActions,
) {
	val align = if (centered) TextAlign.Center else TextAlign.Start
	Text(
		text = manga.title,
		style = if (centered) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
		fontWeight = FontWeight.Bold,
		color = MaterialTheme.colorScheme.onSurface,
		textAlign = align,
		maxLines = 4,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier.clickable { actions.onTitleClick(manga.title) },
	)
	val altTitle = manga.altTitles.firstOrNull()?.takeIf { it.isNotBlank() }
	if (altTitle != null) {
		Spacer(Modifier.height(6.dp))
		Text(
			text = altTitle,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = align,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
	val authors = manga.authors.filter { it.isNotBlank() }
	if (authors.isNotEmpty()) {
		Spacer(Modifier.height(8.dp))
		Text(
			text = authors.joinToString(", "),
			style = MaterialTheme.typography.labelLarge,
			color = accent,
			fontWeight = FontWeight.Medium,
			textAlign = align,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.clickable { actions.onAuthorClick(authors.first()) },
		)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatPills(
	centered: Boolean,
	showContentRating: Boolean,
	manga: Manga,
	details: MangaDetails?,
	sourceTitle: String?,
	accent: Color,
	imageLoader: ImageLoader,
	onSourceClick: () -> Unit,
) {
	val ctx = LocalContext.current
	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = if (centered) {
			Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
		} else {
			Arrangement.spacedBy(8.dp)
		},
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (manga.hasRating) {
			Pill(text = String.format(Locale.ROOT, "%.1f", manga.rating * 5f), accent = accent, highlighted = true) {
				Icon(
					painter = painterResource(R.drawable.ic_star_small),
					contentDescription = null,
					tint = accent,
					modifier = Modifier.size(15.dp),
				)
			}
		}
		manga.state?.let { state ->
			Pill(text = stringResource(state.titleResId), accent = accent)
		}
		if (showContentRating) {
			when (manga.contentRating) {
				ContentRating.SUGGESTIVE -> Pill(text = "16+", accent = accent)
				ContentRating.ADULT -> Pill(text = "18+", accent = accent, highlighted = true)
				else -> Unit
			}
		}
		val locale = details?.getLocale()
		if (locale != null) {
			Pill(text = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }, accent = accent) {
				Icon(
					painter = painterResource(R.drawable.ic_language),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(15.dp),
				)
			}
		}
		if (!manga.isLocal) {
			val srcText = sourceTitle?.takeUnless { it.isBlank() } ?: manga.source.getTitle(ctx)
			val faviconRequest = remember(manga.source) {
				ImageRequest.Builder(ctx)
					.data(manga.source.faviconUri())
					.mangaSourceExtra(manga.source)
					.crossfade(true)
					.build()
			}
			Pill(text = srcText, accent = accent, onClick = onSourceClick) {
				AsyncImage(
					model = faviconRequest,
					imageLoader = imageLoader,
					contentDescription = null,
					error = painterResource(R.drawable.ic_manga_source),
					fallback = painterResource(R.drawable.ic_manga_source),
					modifier = Modifier
						.size(16.dp)
						.clip(RoundedCornerShape(4.dp)),
				)
			}
		}
	}
}

@Composable
private fun Pill(
	text: String,
	accent: Color,
	highlighted: Boolean = false,
	onClick: (() -> Unit)? = null,
	leading: (@Composable () -> Unit)? = null,
) {
	val container = if (highlighted) accent.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceContainerHigh
	val content = if (highlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant
	Surface(
		shape = RoundedCornerShape(50),
		color = container,
		modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			leading?.invoke()
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = content,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun FavouriteButton(
	label: String,
	isFavourite: Boolean,
	accent: Color,
	onClick: () -> Unit,
	horizontalPadding: Dp = SCREEN_PADDING,
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(20.dp),
		color = if (isFavourite) accent else accent.copy(alpha = 0.16f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = horizontalPadding)
			.height(52.dp),
	) {
		val contentColor = if (isFavourite) {
			if (accent.luminanceIsLight()) Color.Black else Color.White
		} else {
			accent
		}
		Row(
			modifier = Modifier.fillMaxSize(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			Icon(
				painter = painterResource(if (isFavourite) R.drawable.ic_heart else R.drawable.ic_heart_outline),
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(8.dp))
			Text(
				text = label,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = contentColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun ProgressCard(historyInfo: HistoryInfo, isLoading: Boolean, accent: Color) {
	val ctx = LocalContext.current
	val res = ctx.resources
	val chaptersText = when {
		isLoading -> stringResource(R.string.loading_)
		historyInfo.currentChapter >= 0 -> withTime(
			stringResource(R.string.chapter_d_of_d, historyInfo.currentChapter + 1, historyInfo.totalChapters),
			historyInfo, res,
		)
		historyInfo.totalChapters == 0 -> stringResource(R.string.no_chapters)
		historyInfo.totalChapters == -1 -> stringResource(R.string.error_occurred)
		else -> withTime(
			pluralStringResource(R.plurals.chapters, historyInfo.totalChapters, historyInfo.totalChapters),
			historyInfo, res,
		)
	}
	val hasHistory = historyInfo.history != null
	val percent = historyInfo.percent.coerceIn(0f, 1f)
	val showProgress = hasHistory && percent > 0f
	val displayPercent = if (ReadingProgress.isCompleted(historyInfo.percent)) 100 else (percent * 100f).toInt()

	SectionCard {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_read),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(22.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = chaptersText,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (showProgress) {
				Text(
					text = stringResource(R.string.percent_string_pattern, displayPercent.toString()),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = accent,
				)
			}
		}
		if (showProgress) {
			Spacer(Modifier.height(14.dp))
			WavyProgressBar(
				progress = percent,
				color = accent,
				trackColor = accent.copy(alpha = 0.22f),
				modifier = Modifier
					.fillMaxWidth()
					.height(14.dp),
			)
		}
	}
}

/**
 * A Material 3 Expressive style "squiggly" progress indicator: the played portion is an animated
 * sine wave, the remaining portion a rounded straight track. (The stock wavy indicator only ships
 * in material3 1.4+, so this is drawn by hand to get the same look on the current version.)
 */
@Composable
private fun WavyProgressBar(progress: Float, color: Color, trackColor: Color, modifier: Modifier) {
	val transition = rememberInfiniteTransition(label = "wave")
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = (2f * PI).toFloat(),
		animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
		label = "phase",
	)
	val animatedProgress by animateFloatAsState(
		targetValue = progress.coerceIn(0f, 1f),
		animationSpec = tween(600),
		label = "progress",
	)
	Canvas(modifier = modifier) {
		val midY = size.height / 2f
		val stroke = 4.5.dp.toPx()
		val activeW = size.width * animatedProgress
		val amplitude = (size.height / 2f - stroke / 2f) * 0.9f
		// Match the Material wavy LinearProgressIndicator used by the download list (its default
		// active-wave wavelength, m3_comp_progress_indicator_linear_active_indicator_wave_wavelength).
		val waveLength = 40.dp.toPx()
		if (animatedProgress < 1f) {
			drawLine(
				color = trackColor,
				start = Offset(activeW, midY),
				end = Offset(size.width, midY),
				strokeWidth = stroke,
				cap = StrokeCap.Round,
			)
		}
		if (activeW > 0f) {
			val path = Path().apply {
				moveTo(0f, midY + amplitude * sin(phase))
				var x = 0f
				while (x <= activeW) {
					lineTo(x, midY + amplitude * sin((x / waveLength) * 2f * PI.toFloat() + phase))
					x += 3f
				}
			}
			drawPath(
				path = path,
				color = color,
				style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
			)
		}
	}
}

@Composable
private fun DescriptionCard(description: CharSequence?) {
	val text = description?.toString()?.trim().orEmpty()
	var expanded by rememberSaveable { mutableStateOf(false) }
	SectionCard {
		Text(
			text = stringResource(R.string.description),
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Spacer(Modifier.height(10.dp))
		Text(
			text = text.ifEmpty { stringResource(R.string.no_description) },
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = if (expanded) Int.MAX_VALUE else 5,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.fillMaxWidth()
				.animateContentSize()
				.clickable(enabled = text.isNotEmpty()) { expanded = !expanded },
		)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(tags: Set<MangaTag>, accent: Color, onTagClick: (MangaTag) -> Unit) {
	if (tags.isEmpty()) return
	var expanded by rememberSaveable { mutableStateOf(false) }
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	val chipStyle = MaterialTheme.typography.labelLarge

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING, vertical = 7.dp),
	) {
		// Work out up-front whether the genres actually wrap past the collapsed row budget — only then
		// is an expand/collapse toggle meaningful. Relying on FlowRow's own indicator alone reserves a
		// slot on the last row and pops in a dead toggle for tag sets that already fit in <= 3 rows.
		val needsToggle = remember(tags, maxWidth, chipStyle) {
			with(density) {
				val available = maxWidth.toPx()
				val chipHorizontalPadding = 28.dp.toPx() // 14.dp on each side, mirrors the chip below
				val gap = 8.dp.toPx()
				var rows = 1
				var rowWidth = 0f
				for (tag in tags) {
					val chipWidth = measurer.measure(tag.title, chipStyle).size.width + chipHorizontalPadding
					rowWidth = when {
						rowWidth == 0f -> chipWidth
						rowWidth + gap + chipWidth <= available -> rowWidth + gap + chipWidth
						else -> {
							rows++
							chipWidth
						}
					}
				}
				rows > TAGS_COLLAPSED_ROWS
			}
		}

		FlowRow(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
			maxLines = if (needsToggle && !expanded) TAGS_COLLAPSED_ROWS else Int.MAX_VALUE,
			overflow = if (needsToggle) {
				FlowRowOverflow.expandOrCollapseIndicator(
					expandIndicator = {
						TagToggleChip(text = stringResource(R.string.more), accent = accent, expanded = false) { expanded = true }
					},
					collapseIndicator = {
						TagToggleChip(text = stringResource(R.string.collapse), accent = accent, expanded = true) { expanded = false }
					},
				)
			} else {
				FlowRowOverflow.Visible
			},
		) {
			// Tint the genre chips from the page accent (themed primary, or the cover colour when
			// "colors from cover" is on) so they follow the active theme instead of the baseline
			// secondary container, which renders purple under the default colour scheme.
			tags.forEach { tag ->
				Surface(
					shape = RoundedCornerShape(15.dp),
					color = accent.copy(alpha = 0.16f),
					onClick = { onTagClick(tag) },
				) {
					Text(
						text = tag.title,
						style = MaterialTheme.typography.labelLarge,
						color = accent,
						modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun TagToggleChip(text: String, accent: Color, expanded: Boolean, onClick: () -> Unit) {
	// Deliberately distinct from the filled tonal genre chips: an outlined chip with a chevron, so it
	// reads as an action (expand / collapse) rather than just another genre tag.
	Surface(
		shape = RoundedCornerShape(15.dp),
		color = Color.Transparent,
		border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
		onClick = onClick,
	) {
		Row(
			modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = text,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = accent,
			)
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = accent,
				modifier = Modifier
					.size(18.dp)
					.rotate(if (expanded) 180f else 0f),
			)
		}
	}
}

@Composable
private fun ScrobblingSection(
	items: List<ScrobblingInfo>,
	imageLoader: ImageLoader,
	accent: Color,
	onMore: () -> Unit,
) {
	SectionHeader(title = stringResource(R.string.tracking), action = stringResource(R.string.manage), accent = accent, onAction = onMore)
	LazyRow(
		contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		items(items, key = { it.scrobbler.name + it.targetId }) { info ->
			Surface(
				shape = RoundedCornerShape(20.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				onClick = onMore,
				modifier = Modifier.width(230.dp),
			) {
				Row(
					modifier = Modifier.padding(12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					AsyncImage(
						model = info.coverUrl,
						imageLoader = imageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.size(48.dp, 66.dp)
							.clip(RoundedCornerShape(12.dp)),
					)
					Spacer(Modifier.width(12.dp))
					Column {
						Text(
							text = info.title,
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurface,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis,
						)
						info.status?.let { status ->
							Spacer(Modifier.height(4.dp))
							Text(
								text = status.name.lowercase().replaceFirstChar { it.uppercase() },
								style = MaterialTheme.typography.bodySmall,
								color = accent,
							)
						}
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelatedSection(
	items: List<MangaListModel>,
	imageLoader: ImageLoader,
	accent: Color,
	onMore: () -> Unit,
	onItemClick: (MangaListModel) -> Unit,
) {
	SectionHeader(title = stringResource(R.string.related_manga), action = stringResource(R.string.show_all), accent = accent, onAction = onMore)
	val carouselState = rememberCarouselState { items.size }
	HorizontalMultiBrowseCarousel(
		state = carouselState,
		preferredItemWidth = 150.dp,
		itemSpacing = 10.dp,
		// The carousel's default single-advance fling only moves one item per swipe — that's what felt
		// sticky and stopped abruptly. The multi-browse fling lets a flick coast across several covers
		// with momentum and settle on a clean item edge, while keeping the M3 multi-browse mask/peek look.
		flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(state = carouselState),
		contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
		modifier = Modifier
			.fillMaxWidth()
			.height(232.dp),
	) { i ->
		val item = items[i]
		Column(
			modifier = Modifier.clickable { onItemClick(item) },
		) {
			AsyncImage(
				model = item.coverUrl,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.height(200.dp)
					.fillMaxWidth()
					.maskClip(RoundedCornerShape(20.dp)),
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = item.title,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(start = 8.dp, end = 4.dp),
			)
		}
	}
}

@Composable
private fun LocalSizeRow(size: Long, manga: Manga, onClick: (Manga) -> Unit) {
	val ctx = LocalContext.current
	SectionCard(onClick = { onClick(manga) }) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_storage_checked),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = FileSize.BYTES.format(ctx, size),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
	}
}

@Composable
private fun SectionHeader(title: String, action: String, accent: Color, onAction: () -> Unit) {
	Spacer(Modifier.height(8.dp))
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		Surface(
			shape = RoundedCornerShape(50),
			color = accent.copy(alpha = 0.14f),
			onClick = onAction,
		) {
			Text(
				text = action,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = accent,
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
			)
		}
	}
	Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionCard(
	onClick: (() -> Unit)? = null,
	content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
	val base = Modifier
		.fillMaxWidth()
		.padding(horizontal = SCREEN_PADDING, vertical = 8.dp)
	Surface(
		shape = RoundedCornerShape(CARD_CORNER),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
	) {
		Column(modifier = Modifier.padding(20.dp), content = content)
	}
}

@Composable
private fun LoadingHero() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(240.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = stringResource(R.string.loading_),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

private fun Color.luminanceIsLight(): Boolean =
	(0.299f * red + 0.587f * green + 0.114f * blue) > 0.5f

private fun withTime(base: String, info: HistoryInfo, res: android.content.res.Resources): String {
	val time = info.estimatedTime?.formatShort(res) ?: return base
	return res.getString(R.string.chapters_time_pattern, base, time)
}
