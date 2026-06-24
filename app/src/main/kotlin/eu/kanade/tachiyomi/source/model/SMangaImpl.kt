@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

class SMangaImpl : SManga {
	override lateinit var url: String
	override lateinit var title: String
	override var artist: String? = null
	override var author: String? = null
	override var description: String? = null

	@Deprecated("Use genres instead", replaceWith = ReplaceWith("genres"))
	override var genre: String? = null

	// extensions-lib 1.5+ fields. These MUST be backed by real storage here: the interface
	// declares them with stub get()/set(_) {} defaults, so without these overrides any value a
	// modern extension writes (e.g. `manga.contentRating = ADULT`, altTitles, score) is silently
	// dropped. `genres` is intentionally NOT overridden — its interface default round-trips through
	// the backing `genre` string, which is what we want.
	override var altTitles: List<String> = emptyList()
	override var banner: String? = null
	override var contentRating: SManga.ContentRating = SManga.ContentRating.SAFE
	override var score: Int? = null
	override var readingMode: SManga.ReadingMode? = null
	override var memo: Map<String, String> = emptyMap()

	override var status: Int = 0
	override var thumbnail_url: String? = null
	override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
	override var initialized: Boolean = false
}
