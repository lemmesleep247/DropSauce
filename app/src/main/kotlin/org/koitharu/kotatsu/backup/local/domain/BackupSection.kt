package org.koitharu.kotatsu.backup.local.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import java.util.Locale
import java.util.zip.ZipEntry

enum class BackupSection(
	val entryName: String,
	@StringRes val titleResId: Int,
) {

	INDEX("index", R.string.app_name),
	HISTORY("history", R.string.history),
	CATEGORIES("categories", R.string.categories),
	FAVOURITES("favourites", R.string.favourites),
	BOOKMARKS("bookmarks", R.string.bookmarks),
	SETTINGS("settings", R.string.settings),
	SETTINGS_READER_GRID("reader_grid", R.string.reader_actions),
	SOURCES("sources", R.string.remote_sources),
	SOURCE_SETTINGS("source_settings", R.string.backup_include_source_settings),
	SCROBBLING("scrobbling", R.string.tracking),
	STATS("statistics", R.string.reading_stats),
	CHAPTERS("chapters", R.string.chapters),
	;

	companion object {

		fun of(entry: ZipEntry): BackupSection? {
			val name = entry.name.lowercase(Locale.ROOT)
			return entries.find { it.entryName == name }
		}
	}
}
