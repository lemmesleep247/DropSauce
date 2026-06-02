@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.mihon

import android.util.Log
import androidx.core.net.toUri
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.model.toManga
import org.koitharu.kotatsu.mihon.model.toMangaChapter
import org.koitharu.kotatsu.mihon.model.toMangaPage
import org.koitharu.kotatsu.mihon.model.toSChapter
import org.koitharu.kotatsu.mihon.model.toSManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.io.IOException
import java.util.EnumSet

@OptIn(InternalParsersApi::class)
class MihonMangaRepository(
	override val source: MihonMangaSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

	companion object {
		private const val TAG = "MihonMangaRepository"
		private const val MIHON_URL_SCHEME = "mihon"
		private const val MIHON_RESOLVE_HOST = "resolve"
		private const val MIHON_IMAGE_HOST = "image"
		private const val QUERY_PAGE_URL = "page_url"
		private const val QUERY_IMAGE_URL = "image_url"
		private const val QUERY_INDEX = "index"
	}

	val mihonSource = source.catalogueSource
	private var lastOffset = -1
	private var currentPage = 1
	private val paginationLock = Any()
	@Volatile private var hasMorePages = true

	override val sortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (source.supportsLatest) add(SortOrder.UPDATED)
		add(SortOrder.RELEVANCE)
	}.let { EnumSet.copyOf(it) }

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		val page = synchronized(paginationLock) {
			if (offset == 0) {
				currentPage = 1
				lastOffset = 0
				hasMorePages = true
			} else if (offset > lastOffset) {
				lastOffset = offset
				currentPage += 1
			}
			currentPage
		}

		// Source told us on the last page that there are no more results — skip the request.
		if (offset > 0 && !hasMorePages) return@withContext emptyList()

		val query = filter?.query?.trim().orEmpty()

		val hasFilters = filter?.let {
			it.query?.isNotBlank() == true || it.tags.isNotEmpty() || it.tagsExclude.isNotEmpty()
		} ?: false

		val mangasPage = when {
			hasFilters -> {
				mihonSource.getSearchManga(page, query, filter?.toMihonFilterList() ?: FilterList())
			}
			order == SortOrder.UPDATED && source.supportsLatest -> mihonSource.getLatestUpdates(page)
			else -> mihonSource.getPopularManga(page)
		}

		// Remember whether the source has more pages for the next getList() call.
		hasMorePages = mangasPage.hasNextPage

		val httpSource = mihonSource as? HttpSource
		mangasPage.mangas.map { sManga ->
			sManga.toManga(
				source = source,
				publicUrl = httpSource?.getMangaUrl(sManga).orEmpty(),
			)
		}
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val sManga = manga.toSManga()

		val details = try {
			mihonSource.getMangaDetails(sManga)
		} catch (e: Exception) {
			if ((e is IOException || e.cause is IOException)
				&& e !is CloudFlareException) {
				delay(500)
				mihonSource.getMangaDetails(sManga)
			} else {
				throw e
			}
		}

		val rawChapters = try {
			mihonSource.getChapterList(sManga)
		} catch (e: Exception) {
			if ((e is IOException || e.cause is IOException)
				&& e !is CloudFlareException) {
				delay(500)
				mihonSource.getChapterList(sManga)
			} else {
				throw e
			}
		}

		// Deduplicate by URL — some sources accidentally return the same chapter twice.
		val uniqueChapters = rawChapters.distinctBy { it.url }

		val httpSource = mihonSource as? HttpSource

		// Let the extension normalize chapter data (title, scanlator, chapter number, etc.)
		// before we process them.  HttpSource.prepareNewChapter() is a no-op by default but
		// many extensions override it.
		if (httpSource != null) {
			uniqueChapters.forEach { sChapter -> httpSource.prepareNewChapter(sChapter, sManga) }
		}

		Log.d(TAG, "rawChapters count: ${rawChapters.size} (unique: ${uniqueChapters.size}), source: ${source.name}")

		// Reverse raw chapters (assuming newest-first from source) and assign virtual numbers
		val chapters = uniqueChapters.asReversed()
			.mapIndexed { index, sChapter ->
				val chapterNumber = if (sChapter.chapter_number >= 0) {
					sChapter.chapter_number
				} else {
					(index + 1).toFloat()
				}
				sChapter.toMangaChapter(source, chapterNumber)
			}
			.sortedBy { it.number }

		// Fallback for missing details fields
		details.url = sManga.url

		val detailsTitle = try { details.title } catch (_: UninitializedPropertyAccessException) { "" }
		if (detailsTitle.isBlank()) {
			details.title = sManga.title
		}

		val detailsThumb = try { details.thumbnail_url } catch (_: UninitializedPropertyAccessException) { null }
		val searchThumb = try { sManga.thumbnail_url } catch (_: UninitializedPropertyAccessException) { null }

		if (detailsThumb.isNullOrBlank() || detailsThumb == details.url || detailsThumb == sManga.url) {
			if (!searchThumb.isNullOrBlank()) {
				details.thumbnail_url = searchThumb
			}
		}

		val publicUrl = httpSource?.getMangaUrl(details).orEmpty()

		details.toManga(
			source = source,
			chapters = chapters,
			publicUrl = publicUrl,
		).copy(id = manga.id)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val sChapter = chapter.toSChapter()
		val pages = try {
			mihonSource.getPageList(sChapter)
		} catch (e: Exception) {
			if ((e is IOException || e.cause is IOException) && e !is CloudFlareException) {
				delay(500)
				mihonSource.getPageList(sChapter)
			} else {
				throw e
			}
		}
		pages.mapIndexed { index, page ->
			val mapped = page.toMangaPage(source, chapter.url)
			when {
				page.imageUrl.isNullOrBlank() && page.url.isNotBlank() -> mapped.copy(
					url = buildMihonPageUrl(
						host = MIHON_RESOLVE_HOST,
						pageUrl = page.url,
						imageUrl = null,
						index = index,
					),
				)

				!page.imageUrl.isNullOrBlank() && page.url.isNotBlank() && page.url != page.imageUrl -> mapped.copy(
					url = buildMihonPageUrl(
						host = MIHON_IMAGE_HOST,
						pageUrl = page.url,
						imageUrl = page.imageUrl,
						index = index,
					),
				)

				else -> mapped
			}
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
		val httpSource = mihonSource as? HttpSource ?: return@withContext page.url
		val ref = page.url.toMihonPageRef() ?: return@withContext page.url
		when (ref.host) {
			MIHON_RESOLVE_HOST -> httpSource.getImageUrl(Page(ref.index, ref.pageUrl))
			MIHON_IMAGE_HOST -> ref.imageUrl ?: page.url
			else -> page.url
		}
	}

	override suspend fun getImageRequestHeaders(imageUrl: String, page: MangaPage): Headers? {
		val httpSource = (mihonSource as? HttpSource) ?: return null
		return runCatching { httpSource.getImageHeaders(page.toMihonPage(imageUrl)) }.getOrNull()
	}

	/**
	 * Delegates image fetching to the extension's [HttpSource.getImage].
	 * Extensions may override [getImage] to decrypt or un-scramble images before returning
	 * them — bypassing this would deliver encrypted bytes to the decoder, causing failures
	 * like "unsupported image format: image/jpeg".
	 */
	override suspend fun getImageStream(pageUrl: String, page: MangaPage): Response? =
		withContext(Dispatchers.IO) {
			val httpSource = mihonSource as? HttpSource ?: return@withContext null
			httpSource.getImage(page.toMihonPage(pageUrl))
		}

	private fun MangaPage.toMihonPage(imageUrl: String): Page {
		val ref = url.toMihonPageRef()
		val pageUrl = ref?.pageUrl ?: imageUrl
		val index = ref?.index ?: 0
		return Page(
			index = index,
			url = pageUrl,
			imageUrl = imageUrl,
		)
	}

	private fun buildMihonPageUrl(
		host: String,
		pageUrl: String,
		imageUrl: String?,
		index: Int,
	): String = android.net.Uri.Builder()
		.scheme(MIHON_URL_SCHEME)
		.authority(host)
		.appendQueryParameter(QUERY_PAGE_URL, pageUrl)
		.apply {
			if (!imageUrl.isNullOrBlank()) {
				appendQueryParameter(QUERY_IMAGE_URL, imageUrl)
			}
			appendQueryParameter(QUERY_INDEX, index.toString())
		}
		.build()
		.toString()

	private fun String.toMihonPageRef(): MihonPageRef? {
		val uri = runCatching { toUri() }.getOrNull() ?: return null
		if (uri.scheme != MIHON_URL_SCHEME) return null
		val pageUrl = uri.getQueryParameter(QUERY_PAGE_URL) ?: return null
		return MihonPageRef(
			host = uri.host.orEmpty(),
			pageUrl = pageUrl,
			imageUrl = uri.getQueryParameter(QUERY_IMAGE_URL),
			index = uri.getQueryParameter(QUERY_INDEX)?.toIntOrNull() ?: 0,
		)
	}

	private data class MihonPageRef(
		val host: String,
		val pageUrl: String,
		val imageUrl: String?,
		val index: Int,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val mihonFilters = try {
			mihonSource.getFilterList()
		} catch (e: Exception) {
			FilterList()
		}
		return MihonFilterMapper.mapOptions(mihonFilters, source)
	}

	private fun MangaListFilter.toMihonFilterList(): FilterList {
		val mihonFilters = try {
			mihonSource.getFilterList()
		} catch (e: Exception) {
			return FilterList()
		}
		MihonFilterMapper.updateMihonFilters(mihonFilters, this)
		return mihonFilters
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = withContext(Dispatchers.IO) {
		val httpSource = mihonSource as? HttpSource
		// Search the same source using the manga's first tag as a query so the
		// results are genre-adjacent. Fall back to popular if there are no tags.
		val query = seed.tags.firstOrNull()?.title.orEmpty()
		val page = if (query.isNotEmpty()) {
			mihonSource.getSearchManga(1, query, FilterList())
		} else {
			mihonSource.getPopularManga(1)
		}
		page.mangas
			.filter { it.url != seed.url }
			.take(10)
			.map { sManga ->
				sManga.toManga(
					source = source,
					publicUrl = httpSource?.getMangaUrl(sManga).orEmpty(),
				)
			}
	}
}
