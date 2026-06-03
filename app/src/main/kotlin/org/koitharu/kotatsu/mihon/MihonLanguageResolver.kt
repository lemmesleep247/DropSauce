package org.koitharu.kotatsu.mihon

/**
 * Picks the active language for a multi-language source. Preference order:
 *  1. The user's stored choice, if still available.
 *  2. The app's current language (base-language match, so "pt" matches "pt-BR").
 *  3. English.
 *  4. The first available language.
 *
 * @param availableLangs distinct language codes offered by the source, in a stable order
 * @param storedLang the user's persisted choice, or null if they haven't chosen one
 * @param appLang the app's current language code (e.g. "en", "fr")
 * @return the chosen language code, or null if [availableLangs] is empty
 */
fun resolveActiveMihonLanguage(
	availableLangs: List<String>,
	storedLang: String?,
	appLang: String,
): String? {
	if (availableLangs.isEmpty()) return null
	if (storedLang != null) {
		availableLangs.firstOrNull { it.equals(storedLang, ignoreCase = true) }?.let { return it }
	}
	matchLanguage(availableLangs, appLang)?.let { return it }
	matchLanguage(availableLangs, "en")?.let { return it }
	return availableLangs.first()
}

private fun matchLanguage(availableLangs: List<String>, target: String): String? {
	if (target.isBlank()) return null
	val targetBase = target.baseLanguage()
	return availableLangs.firstOrNull { it.equals(target, ignoreCase = true) }
		?: availableLangs.firstOrNull { it.baseLanguage().equals(targetBase, ignoreCase = true) }
}

private fun String.baseLanguage(): String = substringBefore('-').substringBefore('_')
