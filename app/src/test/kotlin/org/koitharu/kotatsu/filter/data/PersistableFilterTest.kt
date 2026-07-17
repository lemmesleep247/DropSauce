package org.koitharu.kotatsu.filter.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaListFilter

class PersistableFilterTest {

    @Test
    fun `sort order survives serialization and stays optional for old presets`() {
        val preset = PersistableFilter(
            name = "Unread",
            source = MangaSource("test"),
            filter = MangaListFilter.EMPTY,
            sortOrder = "UPDATED",
        )

        assertEquals(preset, Json.decodeFromString<PersistableFilter>(Json.encodeToString(preset)))
        assertNull(
            Json.decodeFromString<PersistableFilter>(
                Json.encodeToString(preset).replace(",\"sort_order\":\"UPDATED\"", ""),
            ).sortOrder,
        )
    }
}
