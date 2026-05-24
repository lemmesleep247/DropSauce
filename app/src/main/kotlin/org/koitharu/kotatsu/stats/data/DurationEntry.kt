package org.koitharu.kotatsu.stats.data

import androidx.room.ColumnInfo

data class DurationEntry(
	@ColumnInfo(name = "started_at") val startedAt: Long,
	@ColumnInfo(name = "duration") val duration: Long,
)
