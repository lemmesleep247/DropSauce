package org.koitharu.kotatsu.kotatsumigration.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.kotatsumigration.data.MihonTarget
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import javax.inject.Inject

/**
 * Drives migration of restored Kotatsu library entries (built-in sources) onto installed Mihon
 * extensions. [scan] finds candidates; [migrate] resolves the predefined mapping and re-keys the
 * entry onto the mapped Mihon source **offline** via [KotatsuMangaMigrator] (no per-manga network).
 * Live chapter lists load lazily when the user opens each manga.
 */
class KotatsuMigrationUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val sourceMap: KotatsuSourceMap,
	private val mihonExtensionManager: MihonExtensionManager,
	private val mangaDataRepository: MangaDataRepository,
	private val migrator: KotatsuMangaMigrator,
) {

	/** Loads installed extensions so [migrate] can resolve sources. Call once before a batch. */
	suspend fun prepare() {
		mihonExtensionManager.ensureReady()
	}

	/** Restored manga on a built-in (non-Mihon) source that still carry user data. */
	suspend fun scan(): List<LegacyManga> {
		return database.getMangaDao().findLegacyMangaWithUserData().map {
			LegacyManga(
				id = it.manga.id,
				title = it.manga.title,
				sourceName = it.manga.source,
				sourceTitle = it.manga.sourceTitle,
			)
		}
	}

	suspend fun migrate(legacy: LegacyManga): Outcome {
		val target = sourceMap.resolve(legacy.sourceName) ?: return Outcome.NoMapping
		val mihonSource = mihonExtensionManager.getMihonMangaSourceById(target.sourceId)
			?: return Outcome.ExtensionNotInstalled(target)
		val oldManga = mangaDataRepository.findMangaById(legacy.id, withChapters = true)
			?: return Outcome.Failed(target, "Manga ${legacy.id} not found")
		return try {
			migrator(oldManga, mihonSource)
			Outcome.Migrated(target)
		} catch (e: Exception) {
			Outcome.Failed(target, e.message)
		}
	}

	data class LegacyManga(
		val id: Long,
		val title: String,
		val sourceName: String,
		val sourceTitle: String?,
	)

	sealed interface Outcome {
		/** Re-keyed successfully onto [target]. */
		data class Migrated(val target: MihonTarget) : Outcome

		/** A mapping exists but its extension isn't installed. */
		data class ExtensionNotInstalled(val target: MihonTarget) : Outcome

		/** No predefined Mihon equivalent for this Kotatsu source. */
		data object NoMapping : Outcome

		/** Re-keying threw. */
		data class Failed(val target: MihonTarget?, val message: String?) : Outcome
	}
}
