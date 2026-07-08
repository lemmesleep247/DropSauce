package org.koitharu.kotatsu.tracker.data

import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import java.time.Instant

fun TrackLogWithManga.toTrackingLogItem(): TrackingLogItem {
	val names = trackLog.chapters.split('\n')
	val ids = trackLog.chapterIds.split('\n')
	return TrackingLogItem(
		id = trackLog.id,
		chapters = names.mapIndexedNotNull { i, name ->
			if (name.isEmpty()) {
				null
			} else {
				TrackingLogItem.Chapter(id = ids.getOrNull(i)?.toLongOrNull(), name = name)
			}
		},
		manga = manga.toManga(tags.toMangaTags(), null),
		createdAt = Instant.ofEpochMilli(trackLog.createdAt),
		isNew = trackLog.isUnread,
	)
}
