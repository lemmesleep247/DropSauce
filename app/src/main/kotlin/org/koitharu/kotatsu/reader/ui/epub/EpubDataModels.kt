package org.koitharu.kotatsu.reader.ui.epub

import android.text.Spanned
import android.text.SpannedString
import java.io.File
import java.util.zip.ZipFile

internal class NativeChapter(
	val id: Long,
	val title: String,
	val file: File,
	val entryName: String,
) {
	@Volatile
	var content: Spanned? = null
	val text: Spanned get() = content ?: EMPTY_CHAPTER_TEXT
}

internal data class PreparedBook(val chapters: List<NativeChapter>, val archives: Map<File, ZipFile>)
internal data class NativePage(val chapter: Int, val start: Int, val end: Int)
internal data class SearchResult(val chapter: Int, val title: String, val snippet: String, val offset: Int)
internal data class Locator(val chapter: Int, val offset: Int)
internal data class TextLocation(val chapter: Int, val baseOffset: Int)
internal data class SelectedText(val chapter: Int, val start: Int, val end: Int, val text: String)
internal data class DictionaryEntry(val phonetic: String, val meanings: List<DictionaryMeaning>)
internal data class DictionaryMeaning(
	val partOfSpeech: String,
	val definitions: List<DictionaryDefinition>,
	val synonyms: List<String>,
)
internal data class DictionaryDefinition(val text: String, val example: String?)

internal val EMPTY_CHAPTER_TEXT = SpannedString("\u2014")
