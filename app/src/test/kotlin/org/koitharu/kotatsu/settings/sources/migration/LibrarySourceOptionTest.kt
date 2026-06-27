package org.koitharu.kotatsu.settings.sources.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LibrarySourceOptionTest {

	@Test
	fun `same displayed source merges raw keys and counts`() {
		val merged = mergeLibrarySourceOptions(
			listOf(
				option(key = "LEGACY_HITOMI", title = "Hitomi", count = 2, unavailable = true),
				option(key = "MIHON_123:Hitomi", title = "Hitomi", count = 6, unavailable = false),
				option(key = "HITOMI_OLD", title = " hitomi ", count = 3, unavailable = true),
			),
		)

		assertEquals(1, merged.size)
		assertEquals(setOf("LEGACY_HITOMI", "MIHON_123:Hitomi", "HITOMI_OLD"), merged.single().sourceKeys)
		assertEquals(11, merged.single().mangaCount)
		assertFalse(merged.single().isUnavailable)
	}

	private fun option(
		key: String,
		title: String,
		count: Int,
		unavailable: Boolean,
	) = LibrarySourceOption(
		key = key,
		sourceKeys = setOf(key),
		title = title,
		mangaCount = count,
		isUnavailable = unavailable,
		iconSourceKey = key,
		iconUrl = null,
	)
}
