package org.koitharu.kotatsu.tracker.domain.model

import org.koitharu.kotatsu.parsers.model.Manga
import java.time.Instant

data class TrackingLogItem(
	val id: Long,
	val manga: Manga,
	val chapters: List<Chapter>,
	val createdAt: Instant,
	val isNew: Boolean,
) {

	data class Chapter(
		val id: Long?, // null for legacy log rows written before ids were stored
		val name: String,
	)
}
