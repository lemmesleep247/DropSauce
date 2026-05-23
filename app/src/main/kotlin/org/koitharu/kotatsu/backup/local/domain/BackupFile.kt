package org.koitharu.kotatsu.backup.local.domain

import android.net.Uri
import java.util.Date

data class BackupFile(
	val uri: Uri,
	val name: String,
	val dateTime: Date,
) : Comparable<BackupFile> {

	override fun compareTo(other: BackupFile): Int = compareValues(dateTime, other.dateTime)
}
