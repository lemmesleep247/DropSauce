package org.koitharu.kotatsu.local.data

import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File

private fun isZipExtension(ext: String?): Boolean {
	return ext.equals("cbz", ignoreCase = true) || ext.equals("zip", ignoreCase = true)
}

private fun isPdfExtension(ext: String?): Boolean {
	return ext.equals("pdf", ignoreCase = true)
}

private fun isEpubExtension(ext: String?): Boolean {
	return ext.equals("epub", ignoreCase = true)
}

fun hasZipExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext)
}

fun hasPdfExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isPdfExtension(ext)
}

fun hasEpubExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isEpubExtension(ext)
}

fun isSupportedArchive(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext) || isPdfExtension(ext) || isEpubExtension(ext)
}

val File.isZipArchive: Boolean
	get() = isFile && isZipExtension(extension)

val File.isEpubFile: Boolean
	get() = isFile && isEpubExtension(extension)

/** True for local EPUB books: the manga is a single .epub file or its chapters point inside one */
val Manga.isEpub: Boolean
	get() = hasEpubExtension(url.substringBefore('#')) ||
		chapters?.firstOrNull()?.let { hasEpubExtension(it.url.substringBefore('#')) } == true
