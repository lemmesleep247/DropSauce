package org.koitharu.kotatsu.settings.utils

interface AutoCompleteProvider {

	suspend fun getSuggestions(query: String): List<String>
}
