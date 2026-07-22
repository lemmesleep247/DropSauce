package org.koitharu.kotatsu.settings.developer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import eu.kanade.tachiyomi.network.HttpException
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonFilterHost
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.jsoup.HttpStatusException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import kotlin.random.Random

data class ExtensionTestCandidate<T>(
	val packageName: String,
	val extensionName: String,
	val sources: List<T>,
)

data class SelectedExtensionTest<T>(
	val packageName: String,
	val extensionName: String,
	val source: T?,
)

fun <T> selectOneSourcePerExtension(
	candidates: List<ExtensionTestCandidate<T>>,
	random: Random,
): List<SelectedExtensionTest<T>> = candidates.map { candidate ->
	SelectedExtensionTest(
		packageName = candidate.packageName,
		extensionName = candidate.extensionName,
		source = candidate.sources.randomOrNull(random),
	)
}

internal fun extensionDisplayName(name: String): String = name.removePrefix("Tachiyomi: ").trim()

internal fun searchQueryForTitle(title: String): String {
	val words = Regex("[\\p{L}\\p{N}]+").findAll(title).map { it.value }.toList()
	return words.firstOrNull { it.length >= 3 && it.lowercase() !in SEARCH_STOP_WORDS }
		?: words.firstOrNull()
		?: title.trim()
}

internal fun isBlockedTestFailure(error: Throwable): Boolean {
	return generateSequence(error) { it.cause }.any { cause ->
		cause is CloudFlareException ||
			cause is InteractiveActionRequiredException ||
			cause is AuthRequiredException ||
			cause is UnknownHostException ||
			cause is SocketTimeoutException ||
			cause is ConnectException ||
			cause is NoRouteToHostException ||
			cause is SocketException ||
			cause is SSLException ||
			cause is HttpException && cause.code.isAvailabilityStatus() ||
			cause is HttpStatusException && cause.statusCode.isAvailabilityStatus()
	}
}

private fun Int.isAvailabilityStatus(): Boolean = this == 401 || this == 403 || this == 408 || this == 429 || this >= 500

private val SEARCH_STOP_WORDS = setOf("the", "and", "for", "official")

class DeveloperExtensionTestRunner @Inject constructor(
	private val extensionManager: MihonExtensionManager,
	private val repositoryFactory: MangaRepository.Factory,
) {

	suspend fun run(
		onProgress: suspend (completed: Int, total: Int, result: DeveloperExtensionTestResult?) -> Unit,
	): List<DeveloperExtensionTestResult> {
		extensionManager.ensureReady()
		val selected = selectOneSourcePerExtension(
			candidates = extensionManager.installedExtensions.value.map { extension ->
				ExtensionTestCandidate(
					packageName = extension.pkgName,
					extensionName = extensionDisplayName(extension.appName),
					sources = extension.catalogueSources.mapNotNull { source ->
						extensionManager.getMihonMangaSourceById(source.id)
					},
				)
			},
				random = Random.Default,
		)
		onProgress(0, selected.size, null)
		val semaphore = Semaphore(MAX_CONCURRENT_EXTENSIONS)
		val progressMutex = Mutex()
		var completed = 0
		return coroutineScope {
			selected.map { target ->
				async {
					semaphore.withPermit {
						val result = testExtension(target)
						progressMutex.withLock {
							completed++
							onProgress(completed, selected.size, result)
						}
						result
					}
				}
			}.awaitAll()
		}
	}

	private suspend fun testExtension(
		target: SelectedExtensionTest<MihonMangaSource>,
	): DeveloperExtensionTestResult {
		val started = System.nanoTime()
		val source = target.source ?: return DeveloperExtensionTestResult(
			packageName = target.packageName,
			extensionName = target.extensionName,
			sourceName = "No catalogue source",
			language = "",
			stages = listOf(failedStage(STAGE_LOAD, "Extension contains no catalogue source")),
			durationMillis = elapsedMillis(started),
		)
		val repository = repositoryFactory.create(source)
		val stages = mutableListOf<DeveloperTestStageResult>()
		stages += passedStage(STAGE_LOAD, "${source.displayName} (${source.languageDisplayName})")

		val listing = requiredStage(stages, STAGE_LIST) {
			repository.getList(0, SortOrder.POPULARITY, null).requireNotEmpty("Popular listing returned no manga")
		} ?: return result(target, source, stages, started)
		val details = requiredStage(stages, STAGE_DETAILS) {
			findUsableManga(repository, listing)
		} ?: return result(target, source, stages, started)

		requiredStage(stages, STAGE_SEARCH) {
			repository.getList(
				offset = 0,
				order = SortOrder.RELEVANCE,
				filter = MangaListFilter(query = searchQueryForTitle(details.title)),
			)
		}

		val pages = requiredStage(stages, STAGE_PAGES) {
			findUsablePages(repository, details.chapters.orEmpty())
		} ?: return result(target, source, stages, started)

		requiredStage(stages, STAGE_IMAGE) {
			validateAnyPageImage(repository, pages)
		}

		optionalStage(
			stages,
			STAGE_COVER,
			listOfNotNull(details.largeCoverUrl, details.coverUrl).firstOrNull { it.isNotBlank() },
		) { coverUrl ->
			repository.getCoverStream(coverUrl)?.use { response ->
				require(response.isSuccessful) { "Cover request returned HTTP ${response.code}" }
				require(response.body.byteStream().read() != -1) { "Cover response was empty" }
			} ?: error("Source does not expose a cover request")
		}

		optionalStage(stages, STAGE_LATEST, source.takeIf { it.supportsLatest }) {
			repository.getList(0, SortOrder.UPDATED, null)
		}

		requiredStage(stages, STAGE_PAGINATION) {
			repository.getList(listing.size, SortOrder.POPULARITY, null)
		}

		requiredStage(stages, STAGE_FILTERS) {
			(repository as? MihonFilterHost)?.loadDefaultFilterList()
				?: error("Source does not expose its filter list")
		}

		requiredStage(stages, STAGE_RELATED) {
			repository.getRelated(details)
		}

		return result(target, source, stages, started)
	}

	private suspend fun findUsableManga(repository: MangaRepository, listing: List<Manga>): Manga {
		var lastFailure: Throwable? = null
		for (candidate in listing.shuffled().take(MAX_ENTITY_ATTEMPTS)) {
			try {
				val details = repository.getDetails(candidate)
				if (details.chapters?.isNotEmpty() == true) return details
				lastFailure = IllegalStateException("Manga details returned no chapters")
			} catch (e: Throwable) {
				if (e is CancellationException || isBlockedTestFailure(e)) throw e
				lastFailure = e
			}
		}
		throw lastFailure ?: IllegalStateException("No usable manga was found in the popular listing")
	}

	private suspend fun findUsablePages(
		repository: MangaRepository,
		chapters: List<MangaChapter>,
	): List<MangaPage> {
		var lastFailure: Throwable? = null
		for (chapter in chapters.asReversed().take(MAX_CHAPTER_ATTEMPTS)) {
			try {
				val pages = repository.getPages(chapter)
				if (pages.isNotEmpty()) return pages
				lastFailure = IllegalStateException("Chapter returned no pages")
			} catch (e: Throwable) {
				if (e is CancellationException || isBlockedTestFailure(e)) throw e
				lastFailure = e
			}
		}
		throw lastFailure ?: IllegalStateException("Manga details returned no chapters")
	}

	private suspend fun validateAnyPageImage(repository: MangaRepository, pages: List<MangaPage>) {
		var lastFailure: Throwable? = null
		for (page in pages.shuffled().take(MAX_PAGE_ATTEMPTS)) {
			try {
				validatePageImage(repository, page)
				return
			} catch (e: Throwable) {
				if (e is CancellationException || isBlockedTestFailure(e)) throw e
				lastFailure = e
			}
		}
		throw lastFailure ?: IllegalStateException("Chapter returned no pages")
	}

	private suspend fun validatePageImage(repository: MangaRepository, page: MangaPage) {
		val url = repository.getPageUrl(page)
		require(url.isNotBlank()) { "Resolved page URL was empty" }
		repository.getImageStream(url, page)?.use { response ->
			require(response.isSuccessful) { "Image request returned HTTP ${response.code}" }
			require(response.body.byteStream().read() != -1) { "Image response was empty" }
		} ?: error("Source does not expose an image request")
	}

	private suspend fun <T> requiredStage(
		stages: MutableList<DeveloperTestStageResult>,
		name: String,
		block: suspend () -> T,
	): T? {
		val outcome = executeStage(name, block)
		stages += outcome.result
		return outcome.value
	}

	private suspend fun <T : Any> optionalStage(
		stages: MutableList<DeveloperTestStageResult>,
		name: String,
		input: T?,
		block: suspend (T) -> Unit,
	) {
		if (input == null) {
			stages += skippedStage(name, "Not supported")
			return
		}
		requiredStage(stages, name) { block(input) }
	}

	private suspend fun <T> executeStage(name: String, block: suspend () -> T): StageOutcome<T> {
		val started = System.nanoTime()
		return try {
			val value = withTimeout(STAGE_TIMEOUT_MILLIS) { block() }
			StageOutcome(value, passedStage(name, durationMillis = elapsedMillis(started)))
		} catch (_: TimeoutCancellationException) {
			StageOutcome(
				value = null,
				result = DeveloperTestStageResult(
					name = name,
					status = DeveloperTestStageStatus.FAILED,
					message = "Timed out after ${STAGE_TIMEOUT_MILLIS / 1_000} seconds",
					durationMillis = elapsedMillis(started),
				),
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			val status = if (isBlockedTestFailure(e)) {
				DeveloperTestStageStatus.BLOCKED
			} else {
				DeveloperTestStageStatus.FAILED
			}
			StageOutcome(
				value = null,
				result = DeveloperTestStageResult(
					name = name,
					status = status,
					message = e.message?.take(MAX_ERROR_LENGTH) ?: e.javaClass.simpleName,
					durationMillis = elapsedMillis(started),
				),
			)
		}
	}

	private fun result(
		target: SelectedExtensionTest<MihonMangaSource>,
		source: MihonMangaSource,
		stages: List<DeveloperTestStageResult>,
		started: Long,
	) = DeveloperExtensionTestResult(
		packageName = target.packageName,
		extensionName = target.extensionName,
		sourceName = source.displayName,
		language = source.languageDisplayName,
		stages = stages,
		durationMillis = elapsedMillis(started),
	)

	private data class StageOutcome<T>(
		val value: T?,
		val result: DeveloperTestStageResult,
	)

	private companion object {
		const val MAX_CONCURRENT_EXTENSIONS = 2
		const val MAX_ENTITY_ATTEMPTS = 3
		const val MAX_CHAPTER_ATTEMPTS = 3
		const val MAX_PAGE_ATTEMPTS = 3
		const val STAGE_TIMEOUT_MILLIS = 60_000L
		const val MAX_ERROR_LENGTH = 240
		const val STAGE_LOAD = "Extension loading"
		const val STAGE_LIST = "Popular listing"
		const val STAGE_SEARCH = "Search"
		const val STAGE_DETAILS = "Manga details"
		const val STAGE_PAGES = "Chapter pages"
		const val STAGE_IMAGE = "Page image"
		const val STAGE_COVER = "Cover image"
		const val STAGE_LATEST = "Latest listing"
		const val STAGE_PAGINATION = "Pagination"
		const val STAGE_FILTERS = "Filters"
		const val STAGE_RELATED = "Related manga"
	}
}

private fun <T> List<T>.requireNotEmpty(message: String): List<T> = also { require(it.isNotEmpty()) { message } }

private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

private fun passedStage(
	name: String,
	message: String? = null,
	durationMillis: Long = 0,
) = DeveloperTestStageResult(name, DeveloperTestStageStatus.PASSED, message, durationMillis)

private fun failedStage(name: String, message: String) =
	DeveloperTestStageResult(name, DeveloperTestStageStatus.FAILED, message, 0)

private fun skippedStage(name: String, message: String) =
	DeveloperTestStageResult(name, DeveloperTestStageStatus.SKIPPED, message, 0)
