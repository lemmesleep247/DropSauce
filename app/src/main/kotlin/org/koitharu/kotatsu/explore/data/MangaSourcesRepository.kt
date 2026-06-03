package org.koitharu.kotatsu.explore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.resolveActiveMihonLanguage
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/** Result of [MangaSourcesRepository.resolveActiveSource]. */
data class ResolvedSource(
	val source: MangaSource,
	val languageSubtitle: String?,
)

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager? = null,
) {

	private val usageRefresh = MutableStateFlow(0)
	private val pinnedRefresh = MutableStateFlow(0)

	fun getEnabledSources(): List<MangaSource> {
		return buildSortedSourceInfoList(getMihonSources()).map { it.mangaSource }
	}

	/** All installed sources including those disabled via per-extension language toggles
	 *  (still respects the NSFW filter). Used by suggestions when "include disabled sources" is on. */
	fun getAllSources(): List<MangaSource> {
		return buildSortedSourceInfoList(getAllMihonSources()).map { it.mangaSource }
	}

	fun getPinnedSources(): Set<MangaSource> {
		val sourcesByKey = getMihonSources().associateBy(::sourceKeyOf)
		return getPinnedSourceKeys()
			.mapNotNull { sourcesByKey[it] }
			.toSet()
	}

	fun getTopSources(limit: Int): List<MangaSource> {
		return getEnabledSources().take(limit)
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return observeMihonSources().map { it.size }.distinctUntilChanged()
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		// Available sources are provided by extensions
		return kotlinx.coroutines.flow.flowOf(0)
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeMihonSources(),
		settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) { sourcesSortOrder },
		usageRefresh,
		pinnedRefresh,
	) { sources, _, _, _ ->
		buildSortedSourceInfoList(sources)
	}.distinctUntilChanged()

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = observeMihonSources().map { mihon ->
		mihon.map { it to true }
	}

	fun setPositions(sources: List<MangaSource>) {
		// No-op for Mihon-only mode
	}

	fun observeHasNewSources(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = org.koitharu.kotatsu.BuildConfig.VERSION_CODE
	}

	fun isSetupRequired(): Boolean {
		// Setup is not required for Mihon-only mode, extensions are installed separately
		return false
	}

	fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		val before = getPinnedSourceKeys()
		val updated = before.toMutableList()
		for (source in sources) {
			val key = sourceKeyOf(source)
			if (isPinned) {
				updated.remove(key)
				updated.add(0, key)
			} else {
				updated.remove(key)
			}
		}
		setPinnedSourceKeys(updated)
		pinnedRefresh.value++
		return ReversibleHandle {
			setPinnedSourceKeys(before)
			pinnedRefresh.value++
		}
	}

	private val usagePrefs: SharedPreferences by lazy {
		context.getSharedPreferences("source_usage", Context.MODE_PRIVATE)
	}

	fun trackUsage(source: MangaSource) {
		val key = sourceKeyOf(source)
		usagePrefs.edit().putLong(key, System.currentTimeMillis()).apply()
		usageRefresh.value++
	}

	private fun getLastUsedTimestamp(source: MangaSource): Long {
		val key = sourceKeyOf(source)
		return usagePrefs.getLong(key, 0L)
	}

	private val sourceStatePrefs: SharedPreferences by lazy {
		context.getSharedPreferences("source_state", Context.MODE_PRIVATE)
	}

	private fun sourceKeyOf(source: MangaSource): String = when (source) {
		is MangaSourceInfo -> sourceKeyOf(source.mangaSource)
		// Key by package + source name (NOT language) so pins and last-used survive a language switch.
		is MihonMangaSource -> "mihon:${source.pkgName}:${source.catalogueSource.name}"
		else -> {
			val matched = getMihonSources().firstOrNull { it.name == source.name }
			if (matched != null) {
				sourceKeyOf(matched)
			} else {
				source.name
			}
		}
	}

	private fun getPinnedSourceKeys(): List<String> {
		val raw = sourceStatePrefs.getString(KEY_PINNED_ORDER, null).orEmpty()
		if (raw.isEmpty()) return emptyList()
		return raw.split(PIN_SEPARATOR).filter { it.isNotBlank() }
	}

	private fun setPinnedSourceKeys(keys: List<String>) {
		sourceStatePrefs.edit().putString(KEY_PINNED_ORDER, keys.joinToString(PIN_SEPARATOR)).apply()
	}

	private fun buildSortedSourceInfoList(sources: List<MihonMangaSource>): List<MangaSourceInfo> {
		if (sources.isEmpty()) return emptyList()
		val pinnedOrder = getPinnedSourceKeys()
		val pinnedIndex = HashMap<String, Int>(pinnedOrder.size)
		for ((index, key) in pinnedOrder.withIndex()) {
			pinnedIndex[key] = index
		}

		val pinned = ArrayList<MangaSourceInfo>()
		val unpinned = ArrayList<MangaSourceInfo>()
		for (source in sources) {
			val isPinned = sourceKeyOf(source) in pinnedIndex
			val item = MangaSourceInfo(source, isEnabled = true, isPinned = isPinned)
			if (isPinned) pinned += item else unpinned += item
		}

		pinned.sortBy { pinnedIndex[sourceKeyOf(it.mangaSource)] ?: Int.MAX_VALUE }
		when (settings.sourcesSortOrder) {
			SourcesSortOrder.ALPHABETIC -> unpinned.sortWith(compareBy { it.getTitle(context) })
			SourcesSortOrder.LAST_USED -> unpinned.sortWith(compareByDescending { getLastUsedTimestamp(it.mangaSource) })
			SourcesSortOrder.MANUAL -> Unit
		}
		return pinned + unpinned
	}

	fun queryMihonSources(
		query: String?,
		locale: String?,
	): List<MihonMangaSource> {
		val sources = getMihonSources().toMutableList()
		if (settings.isNsfwContentDisabled) {
			sources.removeAll { it.isNsfw }
		}
		if (locale != null) {
			sources.retainAll { it.language == locale }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.displayName.contains(query, ignoreCase = true) ||
					it.pkgName.contains(query, ignoreCase = true)
			}
		}
		sources.sortBy { it.displayName.lowercase() }
		return sources
	}

	/**
	 * Collapses each logical source (a package + source-name pair) into a single Explore entity.
	 * For a multi-language source only the active-language variant is returned — chosen by the
	 * user, or defaulted (app language → English → any) at read time. Single-language sources are
	 * returned as-is. Honours the NSFW filter.
	 */
	private fun getMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val hideNsfw = settings.isNsfwContentDisabled
		val appLang = appLanguage
		return manager.getMihonMangaSources()
			.filterNot { hideNsfw && it.isNsfw }
			.groupBy { it.pkgName to it.catalogueSource.name }
			.mapNotNull { (key, group) ->
				if (group.size == 1) {
					group.first()
				} else {
					val (pkgName, sourceName) = key
					val langs = group.map { it.language }
					val stored = settings.getMihonActiveLang(pkgName, sourceName)
					val activeLang = resolveActiveMihonLanguage(langs, stored, appLang)
					group.firstOrNull { it.language == activeLang } ?: group.first()
				}
			}
	}

	/**
	 * Resolves a (possibly stale) Mihon source to the language variant that is currently active
	 * for its logical source. Non-Mihon or single-language sources are returned unchanged. The
	 * returned [ResolvedSource.languageSubtitle] is the active language's native name, or null
	 * when the source has no language variants.
	 */
	fun resolveActiveSource(source: MangaSource): ResolvedSource {
		val mihon = source.unwrapMihon() ?: return ResolvedSource(source, null)
		val manager = mihonExtensionManager ?: return ResolvedSource(source, null)
		manager.initialize()
		val siblings = manager.getMihonMangaSources()
			.filter { it.pkgName == mihon.pkgName && it.catalogueSource.name == mihon.catalogueSource.name }
		if (siblings.size <= 1) return ResolvedSource(source, null)
		val stored = settings.getMihonActiveLang(mihon.pkgName, mihon.catalogueSource.name)
		val activeLang = resolveActiveMihonLanguage(siblings.map { it.language }, stored, appLanguage)
		val active = siblings.firstOrNull { it.language == activeLang } ?: mihon
		return ResolvedSource(active, active.languageDisplayName)
	}

	private fun MangaSource.unwrapMihon(): MihonMangaSource? = when (this) {
		is MihonMangaSource -> this
		is MangaSourceInfo -> mangaSource as? MihonMangaSource
		else -> null
	}

	/** The app's current language code (e.g. "en", "fr"), used to default a source's language. */
	private val appLanguage: String
		get() = ConfigurationCompat.getLocales(context.resources.configuration)[0]
			?.language
			?.takeIf { it.isNotEmpty() }
			?: "en"

	/** True when at least one installed source offers more than one language. */
	private fun hasMultiLanguageSources(): Boolean {
		val manager = mihonExtensionManager ?: return false
		return manager.getMihonMangaSources()
			.groupBy { it.pkgName to it.catalogueSource.name }
			.any { (_, group) -> group.mapTo(HashSet()) { it.language }.size > 1 }
	}

	private fun getAllMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val sources = manager.getMihonMangaSources()
		val hideNsfw = settings.isNsfwContentDisabled
		return sources.filter { source ->
			!hideNsfw || !source.isNsfw
		}
	}

	fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_MIHON_PER_EXT_ACTIVE_LANG) { mihonPerExtActiveLangs },
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		) { _: Any?, _: Any?, _: Any?, _: Any? ->
			getMihonSources()
		}.distinctUntilChanged()
	}

	/** Emits `true` while any installed source offers more than one language. */
	fun observeHasMultiLanguageSources(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
		) { _: Any?, _: Any? ->
			hasMultiLanguageSources()
		}.distinctUntilChanged()
	}

	/** Emits `true` while the Mihon extension manager is loading extensions, `false` otherwise. */
	fun observeMihonLoadingState(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		return manager.isLoading
	}

	fun observeAllMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		) { _: Any?, _: Any?, _: Any? ->
			getAllMihonSources()
		}.distinctUntilChanged()
	}

	suspend fun reloadMihonSources() {
		mihonExtensionManager?.loadExtensions()
	}

	private companion object {
		private const val KEY_PINNED_ORDER = "pinned_order"
		private const val PIN_SEPARATOR = "\n"
	}
}
