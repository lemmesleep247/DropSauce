package org.koitharu.kotatsu.widget.favorites

import android.content.Context

/**
 * Per-widget-instance storage for the (up to 3) pinned manga ids. Backed by a single
 * SharedPreferences file so we can read and write off the main thread without DB roundtrips —
 * widget host calls onUpdate frequently and DB queries already cover the manga details lookup.
 */
object FavoritesWidgetPrefs {

	const val MAX_PINS = 3
	private const val PREFS_NAME = "favorites_widget"
	private const val KEY_PREFIX = "pins_"

	fun load(context: Context, widgetId: Int): List<Long> {
		val raw = prefs(context).getString(key(widgetId), null) ?: return emptyList()
		return raw.split(',')
			.mapNotNull { it.trim().toLongOrNull() }
			.filter { it != 0L }
			.take(MAX_PINS)
	}

	fun save(context: Context, widgetId: Int, ids: List<Long>) {
		val trimmed = ids.take(MAX_PINS)
		prefs(context).edit()
			.putString(key(widgetId), trimmed.joinToString(","))
			.apply()
	}

	fun clear(context: Context, widgetId: Int) {
		prefs(context).edit().remove(key(widgetId)).apply()
	}

	private fun prefs(context: Context) =
		context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private fun key(widgetId: Int) = KEY_PREFIX + widgetId
}
