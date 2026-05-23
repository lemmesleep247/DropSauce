package org.koitharu.kotatsu.backup.local.domain

import android.content.Context
import androidx.annotation.CheckResult
import org.koitharu.kotatsu.R
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupUtils {

	private const val DIR_BACKUPS = "backups"
	const val MIME_TYPE = "application/zip"
	const val FILE_EXTENSION = ".bk.zip"

	private val dateTimeFormat
		get() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT)

	@CheckResult
	fun createTempFile(context: Context): File {
		val dir = getAppBackupDir(context)
		dir.mkdirs()
		return File(dir, generateFileName(context))
	}

	fun getAppBackupDir(context: Context): File = context.run {
		getExternalFilesDir(DIR_BACKUPS) ?: File(filesDir, DIR_BACKUPS)
	}

	fun parseBackupDateTime(fileName: String): Date? = try {
		val token = fileName.substringAfterLast('_').substringBefore('.')
		dateTimeFormat.parse(token)
	} catch (_: ParseException) {
		null
	}

	fun generateFileName(context: Context): String = buildString {
		append(context.getString(R.string.app_name).replace(' ', '_').lowercase(Locale.ROOT))
		append('_')
		append(dateTimeFormat.format(Date()))
		append(FILE_EXTENSION)
	}
}
