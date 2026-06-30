package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
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
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
	data object Success : SyncResult
	data object SignInRequired : SyncResult

	/** [retryable] is false for errors that won't fix themselves (e.g. a newer remote format). */
	data class Error(val message: String?, val retryable: Boolean = true) : SyncResult
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
		} catch (e: SyncSchemaException) {
			// Remote was written by a newer app version — never overwrite it. Don't retry either.
			Log.e(TAG, "remote schema too new", e)
			syncSettings.lastSyncError = e.message
			return SyncResult.Error(e.message, retryable = false)
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

		// Retry loop for optimistic concurrency: if another device writes the canonical file between
		// our read and our write, we re-read and re-merge so its changes are never lost. Bounded; the
		// last attempt writes best-effort (the per-record merge converges on the next sync regardless).
		var attempt = 0
		while (true) {
			val files = api.findSyncFiles(token)
			val canonical = files.firstOrNull() // oldest file is the single source of truth
			val baseVersion = canonical?.version

			// Download + decode every file. A download failure THROWS out of here — we must never let a
			// transient network/auth error look like "no remote" and overwrite good cloud data locally.
			val remotes = ArrayList<SyncSnapshot>(files.size)
			val decodedIds = HashSet<String>(files.size)
			for (file in files) {
				val bytes = api.download(token, file.id)
				val snapshot = decodeSnapshot(bytes) // null == same-schema corruption → ignored
				if (snapshot != null) {
					remotes += snapshot
					decodedIds += file.id
				}
			}
			val remote = SyncMerger.combine(remotes)
			Log.i(
				TAG,
				"remote: files=${files.size} readable=${remotes.size} fav=${remote?.favourites?.size} " +
					"hist=${remote?.history?.size} cat=${remote?.categories?.size}",
			)

			val configResult = buildMergedConfig(remote?.config, enabled, now)
			val merged = buildMergedSnapshot(remote, configResult.config, now)
			Log.i(
				TAG,
				"merged: fav=${merged.favourites.size} hist=${merged.history.size} cat=${merged.categories.size} " +
					"feed=${merged.feed.size} cfgRev=${merged.config?.revision} remoteCfgWon=${configResult.remoteWon}",
			)
			applyToDatabase(merged, configResult.remoteWon, enabled)

			// Trim tombstones past the retention horizon from what we upload so the file can't grow
			// without bound; local rows are GC'd to match just below.
			val upload = pruneTombstones(merged, now)

			// Nothing to push (single readable file, byte-identical content)? Skip the upload entirely.
			val unchanged = files.size == 1 && remote != null && normalizedJson(upload) == normalizedJson(remote)
			if (unchanged) {
				Log.i(TAG, "no changes to push; skipping upload")
			} else {
				// Concurrency re-check immediately before writing: if the canonical file's version moved
				// since we read it, another device wrote concurrently → re-merge before overwriting.
				if (canonical != null && baseVersion != null && attempt < MAX_CONFLICT_RETRIES) {
					val current = api.getFileVersion(token, canonical.id)
					if (current != null && current != baseVersion) {
						Log.w(TAG, "remote changed during sync (v$baseVersion → v$current); retrying merge")
						attempt++
						continue
					}
				}
				val payload = json.encodeToString(SyncSnapshot.serializer(), upload).encodeToByteArray()
				val fileId = api.upload(token, payload, canonical?.id)
				Log.i(TAG, "uploaded ${payload.size} bytes to $fileId")
				// Collapse first-run duplicates, but ONLY ones we decoded — their data is now merged into
				// this write. An unreadable duplicate is left untouched rather than risk losing data we
				// couldn't parse.
				for (file in files) {
					if (file.id != fileId && file.id in decodedIds) {
						runCatchingCancellable { api.delete(token, file.id) }
							.onSuccess { Log.i(TAG, "removed duplicate sync file ${file.id}") }
							.onFailure { Log.w(TAG, "failed to remove duplicate ${file.id}", it) }
					}
				}
			}

			// Shed old tombstones locally so the next snapshot we build is already trimmed.
			gcOldTombstones(now)

			syncSettings.lastSyncTimestamp = now
			syncSettings.lastSyncError = null
			syncSettings.configRevision = configResult.config.revision
			// Re-hash the ACTUAL local config after applying, so the next sync's change-detection has a
			// truthful baseline (a freshly-adopted remote config must not look "locally changed").
			syncSettings.configHash = configContentHash(dumpLocalConfig(enabled))
			return
		}
	}

	/** Deletes the remote snapshot(s) from Drive and forgets local sync bookkeeping. */
	suspend fun deleteRemoteData(): SyncResult = try {
		val token = auth.requireAccessToken()
		for (file in api.findSyncFiles(token)) {
			runCatchingCancellable { api.delete(token, file.id) }
		}
		syncSettings.lastSyncTimestamp = 0L
		syncSettings.configRevision = 0L
		syncSettings.configHash = null
		SyncResult.Success
	} catch (e: SyncSignInRequiredException) {
		SyncResult.SignInRequired
	} catch (e: Exception) {
		SyncResult.Error(e.message)
	}

	/**
	 * Decodes a downloaded snapshot. Throws [SyncSchemaException] when the file declares a newer schema
	 * than this build understands — so we abort rather than overwrite newer data. Returns null for
	 * same-schema corruption, which the caller safely treats as "no usable remote".
	 */
	private fun decodeSnapshot(bytes: ByteArray): SyncSnapshot? {
		val text = bytes.decodeToString()
		if (text.isBlank()) return null
		// Probe the schema first: a newer format may also fail the full decode, but we still must
		// recognise it as "newer" rather than "corrupt" to avoid clobbering it.
		val version = runCatching {
			json.decodeFromString(SchemaProbe.serializer(), text).schemaVersion
		}.getOrNull()
		if (version != null && version > SyncSnapshot.SCHEMA_VERSION) {
			throw SyncSchemaException(version)
		}
		return try {
			json.decodeFromString(SyncSnapshot.serializer(), text)
		} catch (e: Exception) {
			Log.w(TAG, "remote file unreadable (${e.message}); will overwrite with local data")
			null
		}
	}

	/** Serialized form with volatile per-sync fields zeroed, for an exact "did anything change?" compare. */
	private fun normalizedJson(snapshot: SyncSnapshot): String = json.encodeToString(
		SyncSnapshot.serializer(),
		SyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = "",
			syncedAt = 0L,
			categories = snapshot.categories,
			favourites = snapshot.favourites,
			history = snapshot.history,
			bookmarks = snapshot.bookmarks,
			scrobblings = snapshot.scrobblings,
			tracks = snapshot.tracks,
			feed = snapshot.feed,
			stats = snapshot.stats,
			config = snapshot.config,
		),
	)

	/** Returns a copy with tombstones older than [TOMBSTONE_TTL_MS] dropped from the row sections. */
	private fun pruneTombstones(snapshot: SyncSnapshot, now: Long): SyncSnapshot {
		val cutoff = now - TOMBSTONE_TTL_MS
		val categories = snapshot.categories.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		val favourites = snapshot.favourites.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		val history = snapshot.history.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		if (categories.size == snapshot.categories.size &&
			favourites.size == snapshot.favourites.size &&
			history.size == snapshot.history.size
		) {
			return snapshot
		}
		Log.i(TAG, "pruned tombstones older than ${TOMBSTONE_TTL_MS}ms from upload")
		return SyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = snapshot.deviceId,
			syncedAt = snapshot.syncedAt,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = snapshot.bookmarks,
			scrobblings = snapshot.scrobblings,
			tracks = snapshot.tracks,
			feed = snapshot.feed,
			stats = snapshot.stats,
			config = snapshot.config,
		)
	}

	private suspend fun gcOldTombstones(now: Long) {
		if (syncSettings.isDeletionSyncDisabled) {
			// These rows are the local-only "hidden" markers that stop a cloud pull from restoring an
			// item the user deliberately deleted on this device.
			return
		}
		val cutoff = now - TOMBSTONE_TTL_MS
		runCatchingCancellable {
			database.getFavouritesDao().gc(cutoff)
			database.getFavouriteCategoriesDao().gc(cutoff)
			database.getHistoryDao().gc(cutoff)
		}.onFailure { Log.w(TAG, "local tombstone gc failed", it) }
	}

	suspend fun signOut() {
		// signOut + revokeAccess so the next sign-in shows the account chooser / consent again.
		auth.signOut()
		syncSettings.clearAccount()
	}

	// region snapshot building / merging

	private suspend fun buildMergedSnapshot(
		remote: SyncSnapshot?,
		config: SyncConfig,
		now: Long,
	): SyncSnapshot {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val favEnabled = SyncContent.FAVOURITES in enabled
		val histEnabled = SyncContent.HISTORY in enabled
		val propagateDeletions = !syncSettings.isDeletionSyncDisabled

		val categories = if (favEnabled) {
			SyncMerger.mergeCategories(
				localCategories(),
				remote?.categories.orEmpty(),
				propagateDeletions,
			)
		} else {
			remote?.categories.orEmpty()
		}
		val favourites = if (favEnabled) {
			SyncMerger.mergeFavourites(
				localFavourites(),
				remote?.favourites.orEmpty(),
				propagateDeletions,
			)
		} else {
			remote?.favourites.orEmpty()
		}
		val history = if (histEnabled) {
			SyncMerger.mergeHistory(
				localHistory(),
				remote?.history.orEmpty(),
				propagateDeletions,
			)
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
		val feed = if (SyncContent.FEED in enabled) {
			SyncMerger.mergeFeed(localFeed(), remote?.feed.orEmpty())
		} else {
			remote?.feed.orEmpty()
		}
		val stats = if (SyncContent.STATS in enabled) {
			SyncMerger.mergeStats(localStats(), remote?.stats.orEmpty())
		} else {
			remote?.stats.orEmpty()
		}
		return SyncSnapshot(
			deviceId = syncSettings.deviceId,
			syncedAt = now,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = bookmarks,
			scrobblings = scrobblings,
			tracks = tracks,
			feed = feed,
			stats = stats,
			config = config,
		)
	}

	private class ConfigMergeResult(val config: SyncConfig, val remoteWon: Boolean)

	/**
	 * Merges the config bundle. Two important safety properties:
	 *  1. A device with no baseline hash (never synced) is treated as having made NO local change, so
	 *     it ADOPTS any existing remote config instead of overwriting the cloud with local defaults —
	 *     this is the bug that wiped settings when signing in on a second device.
	 *  2. Each section is unioned by key (winner overrides on conflict) so a key that exists on only
	 *     one device is never dropped. Disabled sections dump empty locally and so pass remote through.
	 *
	 * The whole bundle still resolves by [SyncConfig.revision] (last-writer-wins) when both sides edited
	 * overlapping keys concurrently — a rare case given the multi-hour sync cadence.
	 */
	private suspend fun buildMergedConfig(
		remote: SyncConfig?,
		enabled: Set<SyncContent>,
		now: Long,
	): ConfigMergeResult {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled

		val local = dumpLocalConfig(enabled)
		val currentHash = configContentHash(local)

		val hasBaseline = syncSettings.configHash != null
		val localChanged = hasBaseline && (settingsEnabled || coversEnabled) && currentHash != syncSettings.configHash
		val localRevision = if (localChanged) now else syncSettings.configRevision
		val remoteRevision = remote?.revision ?: -1L
		// Remote wins if it exists and either we made no deliberate local change (adopt the cloud) or
		// its revision is strictly newer. A tie with an unchanged local always goes to remote.
		val remoteWon = remote != null && (!localChanged || remoteRevision > localRevision)

		val merged = SyncConfig(
			revision = maxOf(localRevision, remoteRevision, 0L),
			settings = mergeConfigMap(local.settings, remote?.settings, remoteWon)
				.filterKeys { it !in EXCLUDED_SETTINGS_KEYS },
			readerGrid = mergeConfigMap(local.readerGrid, remote?.readerGrid, remoteWon),
			sourceSettings = mergeConfigList(local.sourceSettings, remote?.sourceSettings, remoteWon) { it.source },
			mangaPrefs = mergeConfigList(local.mangaPrefs, remote?.mangaPrefs, remoteWon) { it.mangaId },
		)
		return ConfigMergeResult(merged, remoteWon)
	}

	private suspend fun dumpLocalConfig(enabled: Set<SyncContent>): SyncConfig {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled
		return SyncConfig(
			revision = 0L,
			settings = if (settingsEnabled) dumpAppSettings() else emptyMap(),
			readerGrid = if (settingsEnabled) dumpReaderGrid() else emptyMap(),
			sourceSettings = if (settingsEnabled) dumpSourceSettings() else emptyList(),
			mangaPrefs = if (coversEnabled) dumpMangaPrefs() else emptyList(),
		)
	}

	/** Union of two maps; the winning side overrides on a shared key. A null remote yields local as-is. */
	private fun <V> mergeConfigMap(local: Map<String, V>, remote: Map<String, V>?, remoteWon: Boolean): Map<String, V> {
		if (remote == null) return local
		val out = LinkedHashMap<String, V>(local.size + remote.size)
		if (remoteWon) {
			out.putAll(local)
			out.putAll(remote)
		} else {
			out.putAll(remote)
			out.putAll(local)
		}
		return out
	}

	/** Union of two lists keyed by [key]; the winning side overrides on a shared key. */
	private inline fun <T, K> mergeConfigList(
		local: List<T>,
		remote: List<T>?,
		remoteWon: Boolean,
		key: (T) -> K,
	): List<T> {
		if (remote == null) return local
		val out = LinkedHashMap<K, T>(local.size + remote.size)
		val first = if (remoteWon) local else remote
		val second = if (remoteWon) remote else local
		for (item in first) out[key(item)] = item
		for (item in second) out[key(item)] = item
		return out.values.toList()
	}

	// endregion

	// region apply to DB

	private suspend fun applyToDatabase(
		merged: SyncSnapshot,
		remoteConfigWon: Boolean,
		enabled: Set<SyncContent>,
	) {
		if (SyncContent.FAVOURITES in enabled) {
			val locallyDeletedCategories = if (syncSettings.isDeletionSyncDisabled) {
				database.getFavouriteCategoriesDao().findAllForSync()
					.filterTo(HashSet()) { it.deletedAt != 0L }
					.mapTo(HashSet()) { it.categoryId }
			} else {
				emptySet()
			}
			val locallyDeletedFavourites = if (syncSettings.isDeletionSyncDisabled) {
				database.getFavouritesDao().findAllForSync()
					.filter { it.deletedAt != 0L }
					.mapTo(HashSet()) { it.mangaId to it.categoryId }
			} else {
				emptySet()
			}
			database.withTransaction {
				for (category in merged.categories) {
					if (category.categoryId !in locallyDeletedCategories) {
						database.getFavouriteCategoriesDao().upsert(category.toEntity())
					}
				}
				for (favourite in merged.favourites) {
					if ((favourite.mangaId to favourite.categoryId) !in locallyDeletedFavourites) {
						upsertManga(favourite.manga)
						database.getFavouritesDao().upsert(favourite.toEntity())
					}
				}
			}
		}
		if (SyncContent.HISTORY in enabled) {
			val locallyDeletedHistory = if (syncSettings.isDeletionSyncDisabled) {
				database.getHistoryDao().findAllForSync()
					.filterTo(HashSet()) { it.deletedAt != 0L }
					.mapTo(HashSet()) { it.mangaId }
			} else {
				emptySet()
			}
			database.withTransaction {
				for (entry in merged.history) {
					if (entry.mangaId !in locallyDeletedHistory) {
						upsertManga(entry.manga)
						database.getHistoryDao().upsertForSync(entry.toEntity())
					}
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
			applyFeed(merged.feed)
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
		// Apply config locally only when the remote bundle won the merge; otherwise local already holds
		// the newest config. We apply the MERGED config (not raw remote) so locally-unique keys survive.
		if (remoteConfigWon) {
			merged.config?.let { applyConfig(it, enabled) }
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
				val currentCover = database.getPreferencesDao().find(pref.mangaId)?.coverUrlOverride
				val resolvedCover = when {
					pref.coverData != null -> materializeCover(pref, currentCover) ?: currentCover
					pref.coverUrlOverride.isPortableCoverUrl() -> pref.coverUrlOverride
					// Schema-1 snapshots only contain the source device's local file URI. Do not
					// replace a working local cover with that unusable path.
					else -> currentCover
				}
				database.getPreferencesDao().upsert(pref.toEntity(resolvedCover))
			}
		}
	}

	private suspend fun applyFeed(feed: List<SyncFeedEntry>) {
		val dao = database.getTrackLogsDao()
		val localByIdentity = dao.findAllForSync().groupBy { entity ->
			SyncMerger.feedIdentity(entity.mangaId, entity.chapters)
		}.toMutableMap()
		for (entry in feed) {
			runCatchingCancellable {
				database.withTransaction {
					upsertManga(entry.manga)
					val identity = SyncMerger.feedIdentity(entry)
					val matches = localByIdentity.remove(identity).orEmpty()
					val keepId = matches.minOfOrNull { it.id } ?: 0L
					dao.insert(entry.toEntity(keepId))
					for (duplicate in matches) {
						if (duplicate.id != keepId) {
							dao.delete(duplicate.id)
						}
					}
				}
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

	private suspend fun localFeed(): List<SyncFeedEntry> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getTrackLogsDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping feed entry(id=${entity.id}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncFeedEntry(entity, manga)
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
		database.getPreferencesDao().getOverrides().sortedBy { it.mangaId }.map { entity ->
			val cover = readCustomCover(entity.coverUrlOverride)
			SyncMangaPrefs(
				entity = entity,
				coverData = cover?.data,
				coverFileExtension = cover?.extension,
			)
		}

	private class EncodedCover(val data: String, val extension: String?)

	private suspend fun readCustomCover(url: String?): EncodedCover? {
		val uri = url?.toUriOrNull() ?: return null
		if (!uri.isFileUri() && uri.scheme != "content") {
			return null
		}
		return withContext(Dispatchers.IO) {
			runCatching {
				val bytes = if (uri.isFileUri()) {
					uri.toFileOrNull()?.takeIf(File::isFile)?.readBytes()
				} else {
					context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
				} ?: return@runCatching null
				val extension = uri.lastPathSegment
					?.substringAfterLast('.', "")
					?.takeIf { it.isSafeFileExtension() }
					?: context.contentResolver.getType(uri)
						?.toMimeTypeOrNull()
						?.let(MimeTypes::getExtension)
				EncodedCover(
					data = Base64.encodeToString(bytes, Base64.NO_WRAP),
					extension = extension,
				)
			}.onFailure {
				Log.w(TAG, "sync: failed to read custom cover '$url'", it)
			}.getOrNull()
		}
	}

	private suspend fun materializeCover(pref: SyncMangaPrefs, previousUrl: String?): String? =
		withContext(Dispatchers.IO) {
			runCatching {
				val bytes = Base64.decode(pref.coverData, Base64.DEFAULT)
				val directory = context.getExternalFilesDir(COVERS_DIR) ?: return@runCatching null
				if (!directory.exists() && !directory.mkdirs()) {
					return@runCatching null
				}
				val digest = MessageDigest.getInstance("SHA-256")
					.digest(bytes)
					.take(12)
					.joinToString("") { "%02x".format(it) }
				val extension = pref.coverFileExtension
					?.takeIf { it.isSafeFileExtension() }
					?.let { ".$it" }
					.orEmpty()
				val destination = File(directory, "sync_${pref.mangaId}_$digest$extension")
				if (!destination.isFile || !destination.readBytes().contentEquals(bytes)) {
					destination.writeBytes(bytes)
				}
				deleteReplacedSyncedCover(previousUrl, destination)
				destination.toUri().toString()
			}.onFailure {
				Log.w(TAG, "sync: failed to restore custom cover for manga ${pref.mangaId}", it)
			}.getOrNull()
		}

	private fun deleteReplacedSyncedCover(previousUrl: String?, replacement: File) {
		val previous = previousUrl?.toUriOrNull()?.toFileOrNull() ?: return
		val coverDirectory = replacement.parentFile?.canonicalFile ?: return
		val oldFile = runCatching { previous.canonicalFile }.getOrNull() ?: return
		if (oldFile != replacement.canonicalFile &&
			oldFile.parentFile == coverDirectory &&
			oldFile.name.startsWith(SYNCED_COVER_PREFIX)
		) {
			oldFile.delete()
		}
	}

	private fun String?.isPortableCoverUrl(): Boolean {
		val uri = this?.toUriOrNull() ?: return true
		return !uri.isFileUri() && uri.scheme != "content"
	}

	private fun String.isSafeFileExtension(): Boolean =
		length in 1..10 && all { it.isLetterOrDigit() }

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

	/** Lightweight probe to read just the schema version before attempting a full decode. */
	@Serializable
	private class SchemaProbe(@SerialName("schema") val schemaVersion: Int = 0)

	private companion object {

		const val TAG = "GDriveSync"

		/**
		 * How long soft-deleted rows (tombstones) are retained in the snapshot and locally before being
		 * garbage-collected. Must comfortably exceed the longest realistic gap between a device's syncs
		 * so every device sees a deletion before its tombstone is dropped; a device offline longer than
		 * this may resurrect an item it never learned was deleted (the accepted trade-off for bounding
		 * the file size).
		 */
		const val TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000 // 60 days

		/** Max times to re-merge when another device writes the file mid-sync, before a best-effort write. */
		const val MAX_CONFLICT_RETRIES = 3

		const val COVERS_DIR = "covers"
		const val SYNCED_COVER_PREFIX = "sync_"

		val EXCLUDED_SETTINGS_KEYS = setOf(
			AppSettings.KEY_APP_PASSWORD,
			AppSettings.KEY_APP_PASSWORD_NUMERIC,
			AppSettings.KEY_PROTECT_APP,
			AppSettings.KEY_PROTECT_APP_TIMEOUT,
			AppSettings.KEY_PROTECT_APP_BIOMETRIC,
			AppSettings.KEY_PROXY_PASSWORD,
			AppSettings.KEY_PROXY_LOGIN,
			AppSettings.KEY_INCOGNITO_MODE,
			AppSettings.KEY_ONBOARDING_COMPLETED,
			AppSettings.KEY_ONBOARDING_INSTALL_ID,
		)
	}
}
