package org.koitharu.kotatsu.settings.tracker

import androidx.room.InvalidationTracker
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import okio.Closeable
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.db.removeObserverAsync
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import javax.inject.Inject

@HiltViewModel
class TrackerSettingsViewModel @Inject constructor(
	private val repository: TrackingRepository,
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
	private val database: MangaDatabase,
) : BaseViewModel() {

	val categoriesCount = MutableStateFlow<IntArray?>(null)
	val categories = favouritesRepository.observeCategories()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	init {
		migrateLegacyDownloadStrategy()
		updateCategoriesCount()
		val databaseObserver = DatabaseObserver(this)
		addCloseable(databaseObserver)
		launchJob(Dispatchers.Default) {
			database.invalidationTracker.addObserver(databaseObserver)
		}
	}

	private fun updateCategoriesCount() {
		launchJob(Dispatchers.Default) {
			categoriesCount.value = repository.getCategoriesCount()
		}
	}

	fun setNewChaptersDownloadCategories(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			favouritesRepository.setNewChaptersDownloadCategories(ids)
		}
	}

	private fun migrateLegacyDownloadStrategy() {
		if (settings.consumeLegacyTrackerDownloadStrategy()) {
			launchJob(Dispatchers.Default) {
				favouritesRepository.enableNewChaptersDownloadForTrackedCategories()
			}
		}
	}

	private class DatabaseObserver(private var vm: TrackerSettingsViewModel?) :
		InvalidationTracker.Observer(arrayOf(TABLE_FAVOURITE_CATEGORIES)),
		Closeable {

		override fun onInvalidated(tables: Set<String>) {
			vm?.updateCategoriesCount()
		}

		override fun close() {
			(vm ?: return).database.invalidationTracker.removeObserverAsync(this)
			vm = null
		}
	}
}
