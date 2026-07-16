package org.koitharu.kotatsu.bookmarks.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubHighlightTest {

	@Test
	fun `highlight text and range survive bookmark encoding`() {
		val url = epubHighlightUrl(42, "Café — کتاب")

		assertEquals(EpubHighlight(7, 42, "Café — کتاب"), decodeEpubHighlight(7, url))
	}
}
