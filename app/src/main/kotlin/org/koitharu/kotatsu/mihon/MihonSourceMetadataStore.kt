package org.koitharu.kotatsu.mihon

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Kotatsu's Manga model has no fields for Mihon's memo/update strategy/initialized state.
 * Persist them out-of-band so recreating a repository/source does not silently erase extension
 * state that Mihon stores in its manga table.
 */
internal class MihonSourceMetadataStore(context: Context) {

	private val preferences = context.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)
	private val json = Json

	fun restore(sourceId: Long, mangaUrl: String, manga: SManga) {
		val prefix = keyPrefix(sourceId, mangaUrl)
		preferences.getString(prefix + KEY_MEMO, null)?.let { encoded ->
			runCatching { json.parseToJsonElement(encoded).jsonObject }
				.getOrNull()
				?.let { manga.memo = it }
		}
		preferences.getString(prefix + KEY_UPDATE_STRATEGY, null)
			?.let { runCatching { UpdateStrategy.valueOf(it) }.getOrNull() }
			?.let { manga.update_strategy = it }
		if (preferences.contains(prefix + KEY_INITIALIZED)) {
			manga.initialized = preferences.getBoolean(prefix + KEY_INITIALIZED, manga.initialized)
		}
	}

	fun save(sourceId: Long, mangaUrl: String, manga: SManga) {
		val prefix = keyPrefix(sourceId, mangaUrl)
		preferences.edit {
			putString(prefix + KEY_MEMO, manga.memo.toString())
			putString(prefix + KEY_UPDATE_STRATEGY, manga.update_strategy.name)
			putBoolean(prefix + KEY_INITIALIZED, manga.initialized)
		}
	}

	/**
	 * New-API extensions (KeiSource/Iken) store the chapter id in SChapter.memo and throw
	 * "Refresh Chapter List" in getPageList when it is missing. Kotatsu's chapter model cannot
	 * carry it, so persist it per (source, chapter url) like the manga sidecar above.
	 */
	fun restoreChapterMemo(sourceId: Long, chapterUrl: String): JsonObject? =
		preferences.getString(keyPrefix(sourceId, chapterUrl) + KEY_MEMO, null)?.let { encoded ->
			runCatching { json.parseToJsonElement(encoded).jsonObject }.getOrNull()
		}

	// ponytail: one prefs entry per chapter, unbounded growth; move to a Room table if the
	// prefs file ever gets noticeably large.
	fun saveChapterMemos(sourceId: Long, memos: Map<String, JsonObject>) {
		if (memos.isEmpty()) return
		preferences.edit {
			for ((chapterUrl, memo) in memos) {
				putString(keyPrefix(sourceId, chapterUrl) + KEY_MEMO, memo.toString())
			}
		}
	}

	private fun keyPrefix(sourceId: Long, mangaUrl: String): String =
		Hash.sha256("$sourceId\n$mangaUrl") + "."

	private companion object {
		const val STORAGE_NAME = "mihon_source_metadata"
		const val KEY_MEMO = "memo"
		const val KEY_UPDATE_STRATEGY = "update_strategy"
		const val KEY_INITIALIZED = "initialized"
	}
}
