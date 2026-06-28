package org.koitharu.kotatsu.mihon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource

class MihonFilterMapperTest {

	private val source = object : MangaSource {
		override val name: String = "MIHON_1"
	}

	@Test
	fun `text filter round trips without losing equals characters`() {
		val defaults = FilterList(TextFilter())
		val workingText = TextFilter().apply { state = "artist=name=value" }
		val encoded = MihonFilterMapper.encode(
			working = FilterList(workingText),
			defaults = defaults,
			source = source,
		)
		val restored = TextFilter()

		MihonFilterMapper.decode(FilterList(restored), MangaListFilter(tags = encoded))

		assertEquals("artist=name=value", restored.state)
	}

	@Test
	fun `explicit resets from non-empty source defaults round trip`() {
		val defaults = FilterList(
			TriFilter(Filter.TriState.STATE_INCLUDE),
			TextFilter("default"),
			SortFilter(Filter.Sort.Selection(1, true)),
		)
		val working = FilterList(
			TriFilter(Filter.TriState.STATE_IGNORE),
			TextFilter(""),
			SortFilter(null),
		)

		val encoded = MihonFilterMapper.encode(working, defaults, source)
		val restoredTri = TriFilter(Filter.TriState.STATE_INCLUDE)
		val restoredText = TextFilter("default")
		val restoredSort = SortFilter(Filter.Sort.Selection(1, true))
		MihonFilterMapper.decode(
			FilterList(restoredTri, restoredText, restoredSort),
			MangaListFilter(tags = encoded),
		)

		assertEquals(Filter.TriState.STATE_IGNORE, restoredTri.state)
		assertEquals("", restoredText.state)
		assertEquals(null, restoredSort.state)
	}

	private class TextFilter(state: String = "") : Filter.Text("Query", state)
	private class TriFilter(state: Int) : Filter.TriState("Genre", state)
	private class SortFilter(state: Filter.Sort.Selection?) : Filter.Sort("Sort", arrayOf("A", "B"), state)
}
