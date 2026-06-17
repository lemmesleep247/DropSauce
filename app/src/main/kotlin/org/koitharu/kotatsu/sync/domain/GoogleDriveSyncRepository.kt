package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sync.data.model.SyncTrack
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sync.data.GoogleDriveApi
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncConfig
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
	data object Success : SyncResult
	data object SignInRequired : SyncResult
	data class Error(val message: String?) : SyncResult
}

/**
 * Orchestrates a full two-way Google Drive sync: pull the remote snapshot, merge it with the local
 * database (per-record, tombstone-aware), apply the merged result locally, then push it back. Row
 * data (favourites/categories/history) propagates deletions via tombstones; the config bundle
 * (settings/reader-grid/source-settings/custom-covers) is last-writer-wins by revision.
 *
 * "What to sync" gates which sections this device reads & writes. Disabled sections are passed
 * through unchanged from the remote snapshot, so opting out on one device never erases another's data.
 */
@Singleton
class GoogleDriveSyncRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val appSettings: AppSettings,
	private val tapGridSettings: TapGridSettings,
	private val syncSettings: SyncSettings,
	private val auth: GoogleDriveAuth,
	private val api: GoogleDriveApi,
) {

	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
	}

	/** Whether a sync is currently running (so the UI can show progress and avoid overlap). */
	val isSyncing = MutableStateFlow(false)
	private val syncMutex = Mutex()

	/** Records the signed-in account. Email/name/photo come straight from GoogleSignIn — no network call. */
	fun onSignedIn(email: String?, displayName: String?, photoUrl: String?) {
		syncSettings.accountEmail = email?.ifBlank { null } ?: "Google Drive"
		syncSettings.accountName = displayName
		syncSettings.accountPhotoUrl = photoUrl
		Log.i(TAG, "signed in as ${syncSettings.accountEmail}")
	}

	suspend fun sync(): SyncResult {
		if (!syncSettings.isSignedIn) return SyncResult.SignInRequired
		if (!syncMutex.tryLock()) return SyncResult.Success
		isSyncing.value = true
		try {
			var token = auth.requireAccessToken()
			try {
				performSync(token)
			} catch (e: SyncApiException) {
				// A cached token can be stale → 401. Refresh once and retry.
				if (e.code == 401) {
					Log.w(TAG, "token rejected (401), refreshing and retrying")
					auth.invalidateToken(token)
					token = auth.requireAccessToken()
					performSync(token)
				} else {
					throw e
				}
			}
			return SyncResult.Success
		} catch (e: SyncSignInRequiredException) {
			Log.w(TAG, "sign-in required", e)
			return SyncResult.SignInRequired
		} catch (e: Exception) {
			Log.e(TAG, "sync failed", e)
			syncSettings.lastSyncError = e.message ?: e.javaClass.simpleName
			return SyncResult.Error(e.message ?: e.javaClass.simpleName)
		} finally {
			isSyncing.value = false
			syncMutex.unlock()
		}
	}

	private suspend fun performSync(token: String) {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val now = System.currentTimeMillis()
		Log.i(TAG, "sync start: enabled=$enabled")

		val remoteFile = api.findSyncFile(token)
		val remote = if (remoteFile != null) {
			val bytes = api.download(token, remoteFile.id)
			try {
				json.decodeFromString(SyncSnapshot.serializer(), bytes.decodeToString())
			} catch (e: Exception) {
				// Remote file is corrupt (bad JSON, schema mismatch after an update, etc.).
				// Overwrite it with local data rather than leaving the sync permanently stuck.
				Log.w(TAG, "remote file unreadable (${e.message}), overwriting with local data")
				null
			}
		} else {
			null
		}
		Log.i(
			TAG,
			"remote: file=${remoteFile?.id != null} fav=${remote?.favourites?.size} " +
				"hist=${remote?.history?.size} cat=${remote?.categories?.size}",
		)

		val merged = buildMergedSnapshot(remote, enabled, now)
		Log.i(
			TAG,
			"merged: fav=${merged.favourites.size} hist=${merged.history.size} " +
				"cat=${merged.categories.size} cfgRev=${merged.config?.revision}",
		)
		applyToDatabase(merged, remote, enabled)

		val payload = json.encodeToString(SyncSnapshot.serializer(), merged).encodeToByteArray()
		val fileId = api.upload(token, payload, remoteFile?.id)
		Log.i(TAG, "uploaded ${payload.size} bytes to $fileId")

		syncSettings.lastSyncTimestamp = now
		syncSettings.lastSyncError = null
		merged.config?.let {
			syncSettings.configRevision = it.revision
			syncSettings.configHash = configContentHash(it)
		}
	}

	/** Deletes the remote snapshot from Drive and forgets local sync bookkeeping. */
	suspend fun deleteRemoteData(): SyncResult = try {
		val token = auth.requireAccessToken()
		api.findSyncFile(token)?.let { api.delete(token, it.id) }
		syncSettings.lastSyncTimestamp = 0L
		syncSettings.configRevision = 0L
		syncSettings.configHash = null
		SyncResult.Success
	} catch (e: SyncSignInRequiredException) {
		SyncResult.SignInRequired
	} catch (e: Exception) {
		SyncResult.Error(e.message)
	}

	suspend fun signOut() {
		// signOut + revokeAccess so the next sign-in shows the account chooser / consent again.
		auth.signOut()
		syncSettings.clearAccount()
	}

	// region snapshot building / merging

	private suspend fun buildMergedSnapshot(
		remote: SyncSnapshot?,
		enabled: Set<SyncContent>,
		now: Long,
	): SyncSnapshot {
		val favEnabled = SyncContent.FAVOURITES in enabled
		val histEnabled = SyncContent.HISTORY in enabled

		val categories = if (favEnabled) {
			SyncMerger.mergeCategories(localCategories(), remote?.categories.orEmpty())
		} else {
			remote?.categories.orEmpty()
		}
		val favourites = if (favEnabled) {
			SyncMerger.mergeFavourites(localFavourites(), remote?.favourites.orEmpty())
		} else {
			remote?.favourites.orEmpty()
		}
		val history = if (histEnabled) {
			SyncMerger.mergeHistory(localHistory(), remote?.history.orEmpty())
		} else {
			remote?.history.orEmpty()
		}
		val bookmarks = if (SyncContent.BOOKMARKS in enabled) {
			SyncMerger.mergeBookmarks(localBookmarks(), remote?.bookmarks.orEmpty())
		} else {
			remote?.bookmarks.orEmpty()
		}
		val scrobblings = if (SyncContent.TRACKING in enabled) {
			SyncMerger.mergeScrobblings(localScrobblings(), remote?.scrobblings.orEmpty())
		} else {
			remote?.scrobblings.orEmpty()
		}
		val tracks = if (SyncContent.FEED in enabled) {
			SyncMerger.mergeTracks(localTracks(), remote?.tracks.orEmpty())
		} else {
			remote?.tracks.orEmpty()
		}
		val stats = if (SyncContent.STATS in enabled) {
			SyncMerger.mergeStats(localStats(), remote?.stats.orEmpty())
		} else {
			remote?.stats.orEmpty()
		}
		val config = buildMergedConfig(remote?.config, enabled, now)
		return SyncSnapshot(
			deviceId = syncSettings.deviceId,
			syncedAt = now,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = bookmarks,
			scrobblings = scrobblings,
			tracks = tracks,
			stats = stats,
			config = config,
		)
	}

	private suspend fun buildMergedConfig(
		remote: SyncConfig?,
		enabled: Set<SyncContent>,
		now: Long,
	): SyncConfig {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled

		val localSettings = if (settingsEnabled) dumpAppSettings() else emptyMap()
		val localGrid = if (settingsEnabled) dumpReaderGrid() else emptyMap()
		val localSources = if (settingsEnabled) dumpSourceSettings() else emptyList()
		val localPrefs = if (coversEnabled) dumpMangaPrefs() else emptyList()

		val localCandidate = SyncConfig(
			revision = 0L,
			settings = localSettings,
			readerGrid = localGrid,
			sourceSettings = localSources,
			mangaPrefs = localPrefs,
		)
		val currentHash = configContentHash(localCandidate)
		val localChanged = (settingsEnabled || coversEnabled) && currentHash != syncSettings.configHash
		val localRevision = if (localChanged) now else syncSettings.configRevision
		val remoteRevision = remote?.revision ?: -1L
		val remoteWon = remote != null && remoteRevision > localRevision

		fun <T> pick(enabledFlag: Boolean, local: T, remoteValue: T?, remoteFallback: T): T = when {
			!enabledFlag -> remoteValue ?: remoteFallback // passthrough remote for disabled sections
			remoteWon -> remoteValue ?: remoteFallback
			else -> local
		}

		return SyncConfig(
			revision = maxOf(localRevision, remoteRevision),
			settings = pick(settingsEnabled, localSettings, remote?.settings, emptyMap()),
			readerGrid = pick(settingsEnabled, localGrid, remote?.readerGrid, emptyMap()),
			sourceSettings = pick(settingsEnabled, localSources, remote?.sourceSettings, emptyList()),
			mangaPrefs = pick(coversEnabled, localPrefs, remote?.mangaPrefs, emptyList()),
		)
	}

	// endregion

	// region apply to DB

	private suspend fun applyToDatabase(
		merged: SyncSnapshot,
		remote: SyncSnapshot?,
		enabled: Set<SyncContent>,
	) {
		if (SyncContent.FAVOURITES in enabled) {
			database.withTransaction {
				for (category in merged.categories) {
					database.getFavouriteCategoriesDao().upsert(category.toEntity())
				}
				for (favourite in merged.favourites) {
					upsertManga(favourite.manga)
					database.getFavouritesDao().upsert(favourite.toEntity())
				}
			}
		}
		if (SyncContent.HISTORY in enabled) {
			database.withTransaction {
				for (entry in merged.history) {
					upsertManga(entry.manga)
					database.getHistoryDao().upsertForSync(entry.toEntity())
				}
			}
		}
		if (SyncContent.BOOKMARKS in enabled) {
			for (group in merged.bookmarks) {
				runCatchingCancellable {
					database.withTransaction {
						upsertManga(group.manga)
						if (group.bookmarks.isNotEmpty()) {
							database.getBookmarksDao().upsert(group.bookmarks.map { it.toEntity() })
						}
					}
				}
			}
		}
		if (SyncContent.FEED in enabled) {
			for (track in merged.tracks) {
				runCatchingCancellable {
					database.withTransaction {
						upsertManga(track.manga)
						database.getTracksDao().upsert(track.toEntity())
					}
				}
			}
		}
		if (SyncContent.TRACKING in enabled) {
			for (entry in merged.scrobblings) {
				runCatchingCancellable { database.getScrobblingDao().upsert(entry.toEntity()) }
			}
		}
		if (SyncContent.STATS in enabled) {
			// FK to history.manga_id — applied after history; tolerate rows whose history is absent.
			for (entry in merged.stats) {
				runCatchingCancellable { database.getStatsDao().upsert(entry.toEntity()) }
			}
		}
		// Config is only applied locally when the remote bundle is the winner; otherwise the local
		// DB already holds the newest config.
		val remoteRevision = remote?.config?.revision ?: -1L
		val remoteConfigWon = remote?.config != null && remoteRevision > syncSettings.configRevision
		if (remoteConfigWon) {
			applyConfig(remote.config, enabled)
		}
	}

	private suspend fun applyConfig(config: SyncConfig, enabled: Set<SyncContent>) {
		if (SyncContent.SETTINGS in enabled) {
			val settings = config.settings.toMutableMap()
			EXCLUDED_SETTINGS_KEYS.forEach { settings.remove(it) }
			appSettings.upsertAll(settings.mapValues { it.value.rawValue() })
			tapGridSettings.upsertAll(config.readerGrid.mapValues { it.value.rawValue() })
			applySourceSettings(config.sourceSettings)
		}
		if (SyncContent.CUSTOM_COVERS in enabled) {
			for (pref in config.mangaPrefs) {
				database.getPreferencesDao().upsert(pref.toEntity())
			}
		}
	}

	private suspend fun upsertManga(manga: MangaBackup) {
		val tags = manga.tags.map { it.toEntity() }
		if (tags.isNotEmpty()) {
			database.getTagsDao().upsert(tags)
		}
		database.getMangaDao().upsert(manga.toEntity(), tags)
	}

	private suspend fun applySourceSettings(list: List<SourceSettingsBackup>) {
		val knownSources = database.getSourcesDao().findAll().mapTo(HashSet()) { it.source }
		for (entry in list) {
			if (entry.source !in knownSources) {
				Log.d(TAG, "sync: skipping source settings for '${entry.source}' — source not installed")
				continue
			}
			val prefsName = SourceSettings.getStorageName(entry.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			prefs.edit {
				entry.values.forEach { (key, primitive) ->
					when (primitive) {
						is BackupPrimitive.StringValue -> putString(key, primitive.value)
						is BackupPrimitive.BoolValue -> putBoolean(key, primitive.value)
						is BackupPrimitive.IntValue -> putInt(key, primitive.value)
						is BackupPrimitive.LongValue -> putLong(key, primitive.value)
						is BackupPrimitive.FloatValue -> putFloat(key, primitive.value)
						is BackupPrimitive.StringSetValue -> putStringSet(key, primitive.value)
					}
				}
			}
		}
	}

	// endregion

	// region local readers

	private suspend fun localCategories(): List<SyncCategory> =
		database.getFavouriteCategoriesDao().findAllForSync().map(::SyncCategory)

	private suspend fun localFavourites(): List<SyncFavourite> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getFavouritesDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping favourite(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncFavourite(entity, manga)
		}
	}

	private suspend fun localHistory(): List<SyncHistory> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getHistoryDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping history(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncHistory(entity, manga)
		}
	}

	private suspend fun localBookmarks(): List<BookmarkBackup> =
		database.getBookmarksDao().dump().toList().map { (manga, items) -> BookmarkBackup(manga, items) }

	private suspend fun localScrobblings(): List<ScrobblingBackup> =
		database.getScrobblingDao().dumpEnabled().toList().map(::ScrobblingBackup)

	private suspend fun localStats(): List<StatsBackup> =
		database.getStatsDao().dumpEnabled().toList().map(::StatsBackup)

	private suspend fun localTracks(): List<SyncTrack> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getTracksDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping track(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncTrack(entity, manga)
		}
	}

	private fun MangaWithTags.toBackup(): MangaBackup = MangaBackup(this)

	private fun dumpAppSettings(): Map<String, BackupPrimitive> {
		val map = appSettings.getAllValues().toMutableMap()
		EXCLUDED_SETTINGS_KEYS.forEach { map.remove(it) }
		return map.toSortedMap().mapNotNullValuesToBackup()
	}

	private fun dumpReaderGrid(): Map<String, BackupPrimitive> =
		tapGridSettings.getAllValues().toSortedMap().mapNotNullValuesToBackup()

	private suspend fun dumpSourceSettings(): List<SourceSettingsBackup> {
		val sources = database.getSourcesDao().findAll()
		val result = ArrayList<SourceSettingsBackup>(sources.size)
		for (source in sources.sortedBy { it.source }) {
			val prefsName = SourceSettings.getStorageName(source.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			val values = prefs.all.toSortedMap().mapNotNullValuesToBackup()
			if (values.isNotEmpty()) {
				result += SourceSettingsBackup(source = source.source, values = values)
			}
		}
		return result
	}

	private suspend fun dumpMangaPrefs(): List<SyncMangaPrefs> =
		database.getPreferencesDao().getOverrides().sortedBy { it.mangaId }.map(::SyncMangaPrefs)

	private fun Map<String, *>.mapNotNullValuesToBackup(): Map<String, BackupPrimitive> {
		val out = LinkedHashMap<String, BackupPrimitive>(size)
		for ((key, value) in this) {
			BackupPrimitive.of(value)?.let { out[key] = it }
		}
		return out
	}

	private fun configContentHash(config: SyncConfig): String {
		val normalized = SyncConfig(
			revision = 0L,
			settings = config.settings.toSortedMap(),
			readerGrid = config.readerGrid.toSortedMap(),
			sourceSettings = config.sourceSettings.sortedBy { it.source },
			mangaPrefs = config.mangaPrefs.sortedBy { it.mangaId },
		)
		return json.encodeToString(SyncConfig.serializer(), normalized).hashCode().toString()
	}

	// endregion

	private companion object {

		const val TAG = "GDriveSync"

		val EXCLUDED_SETTINGS_KEYS = setOf(
			AppSettings.KEY_APP_PASSWORD,
			AppSettings.KEY_APP_PASSWORD_NUMERIC,
			AppSettings.KEY_PROXY_PASSWORD,
			AppSettings.KEY_PROXY_LOGIN,
			AppSettings.KEY_INCOGNITO_MODE,
			AppSettings.KEY_ONBOARDING_COMPLETED,
			AppSettings.KEY_ONBOARDING_INSTALL_ID,
		)
	}
}
