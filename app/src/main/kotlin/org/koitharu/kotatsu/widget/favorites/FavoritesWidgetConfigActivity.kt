package org.koitharu.kotatsu.widget.favorites

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.widget.common.widgetEntryPoint

class FavoritesWidgetConfigActivity : AppCompatActivity() {

	private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
	private var isReconfigure: Boolean = false
	private val selectedIds = ArrayList<Long>(FavoritesWidgetPrefs.MAX_PINS)
	private lateinit var adapter: PickerAdapter

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		widgetId = intent.getIntExtra(
			AppWidgetManager.EXTRA_APPWIDGET_ID,
			AppWidgetManager.INVALID_APPWIDGET_ID,
		)
		isReconfigure = intent.getBooleanExtra(EXTRA_RECONFIGURE, false)

		// Cancel by default — system removes the widget if user backs out of the initial
		// configuration. Reconfigure flow ignores RESULT_CANCELED (the widget already exists).
		setResult(Activity.RESULT_CANCELED, resultIntent())

		if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
			finish()
			return
		}

		setContentView(R.layout.activity_favorites_widget_config)

		val toolbar = findViewById<Toolbar>(R.id.toolbar)
		setSupportActionBar(toolbar)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		toolbar.setNavigationOnClickListener { finish() }
		toolbar.setTitle(R.string.widget_favorites_config_title)

		val empty = findViewById<TextView>(R.id.text_empty)
		val recycler = findViewById<RecyclerView>(R.id.recycler)
		val saveButton = findViewById<MaterialButton>(R.id.button_save)
		val counter = findViewById<TextView>(R.id.text_counter)

		selectedIds.clear()
		selectedIds.addAll(FavoritesWidgetPrefs.load(applicationContext, widgetId))

		val entry = widgetEntryPoint()
		val imageLoader = entry.imageLoader
		adapter = PickerAdapter(
			selected = selectedIds,
			limit = FavoritesWidgetPrefs.MAX_PINS,
			imageLoader = imageLoader,
			onClick = { manga ->
				toggleSelection(manga)
				updateCounter(counter)
			},
		)
		recycler.layoutManager = LinearLayoutManager(this)
		recycler.adapter = adapter

		saveButton.setOnClickListener { commitAndFinish() }
		updateCounter(counter)

		lifecycleScope.launch {
			val (favourites, sourceTitles) = withContext(Dispatchers.IO) {
				runCatching {
					val mangaList = entry.favouritesRepository.getAllManga()
					// Resolve the cached `source_title` column for each manga in parallel.
					// `Manga.source.getTitle(context)` returns "missing extension (unknown)"
					// for Mihon/Tachiyomi-sourced manga because the runtime MangaSource
					// instance doesn't carry the displayName — only the DB does.
					val mangaDao = entry.database.getMangaDao()
					val titles = coroutineScope {
						mangaList.map { manga ->
							async { manga.id to mangaDao.findSourceTitle(manga.id) }
						}.map { it.await() }
					}.toMap()
					mangaList to titles
				}.getOrDefault(emptyList<Manga>() to emptyMap())
			}
			if (favourites.isEmpty()) {
				empty.visibility = View.VISIBLE
				recycler.visibility = View.GONE
				saveButton.isEnabled = selectedIds.isNotEmpty()
			} else {
				empty.visibility = View.GONE
				recycler.visibility = View.VISIBLE
				adapter.submit(favourites, sourceTitles)
			}
		}
	}

	private fun toggleSelection(manga: Manga) {
		if (selectedIds.contains(manga.id)) {
			selectedIds.remove(manga.id)
		} else {
			if (selectedIds.size >= FavoritesWidgetPrefs.MAX_PINS) {
				Toast.makeText(
					this,
					getString(R.string.widget_favorites_limit_reached, FavoritesWidgetPrefs.MAX_PINS),
					Toast.LENGTH_SHORT,
				).show()
				return
			}
			selectedIds.add(manga.id)
		}
		adapter.notifySelectionChanged()
	}

	private fun updateCounter(counter: TextView) {
		counter.text = getString(
			R.string.widget_favorites_pin_counter,
			selectedIds.size,
			FavoritesWidgetPrefs.MAX_PINS,
		)
	}

	private fun commitAndFinish() {
		FavoritesWidgetPrefs.save(applicationContext, widgetId, selectedIds)
		setResult(Activity.RESULT_OK, resultIntent())
		// Tell the widget host to re-render with the new pins.
		val update = Intent(applicationContext, FavoritesWidget::class.java)
			.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
			.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
		sendBroadcast(update)
		finish()
	}

	private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

	private class PickerAdapter(
		private val selected: MutableList<Long>,
		private val limit: Int,
		private val imageLoader: ImageLoader,
		private val onClick: (Manga) -> Unit,
	) : RecyclerView.Adapter<PickerAdapter.VH>() {

		private val items = ArrayList<Manga>()
		private val sourceTitleOverrides = HashMap<Long, String?>()

		fun submit(list: List<Manga>, sourceTitles: Map<Long, String?>) {
			items.clear()
			items.addAll(list)
			sourceTitleOverrides.clear()
			sourceTitleOverrides.putAll(sourceTitles)
			notifyDataSetChanged()
		}

		fun notifySelectionChanged() {
			notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.item_favorites_widget_config, parent, false)
			return VH(view, selected, sourceTitleOverrides, imageLoader, onClick)
		}

		override fun onBindViewHolder(holder: VH, position: Int) {
			holder.bind(items[position])
		}

		override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
			if (payloads.contains(PAYLOAD_SELECTION)) {
				holder.bindSelectionOnly(items[position])
			} else {
				super.onBindViewHolder(holder, position, payloads)
			}
		}

		override fun getItemCount(): Int = items.size

		private class VH(
			itemView: View,
			private val selected: List<Long>,
			private val sourceTitleOverrides: Map<Long, String?>,
			private val imageLoader: ImageLoader,
			private val onClick: (Manga) -> Unit,
		) : RecyclerView.ViewHolder(itemView) {

			private val cover: ImageView = itemView.findViewById(R.id.image_cover)
			private val title: TextView = itemView.findViewById(R.id.text_title)
			private val subtitle: TextView = itemView.findViewById(R.id.text_subtitle)
			private val check: ImageView = itemView.findViewById(R.id.image_check)
			private var current: Manga? = null

			init {
				itemView.setOnClickListener {
					current?.let(onClick)
				}
			}

			fun bind(manga: Manga) {
				current = manga
				title.text = manga.title
				val cachedTitle = sourceTitleOverrides[manga.id]?.takeIf { it.isNotBlank() }
				subtitle.text = cachedTitle ?: manga.source.getTitle(itemView.context)
				cover.setImageResource(R.drawable.ic_widget_cover_placeholder)
				val ctx = itemView.context
				ImageRequest.Builder(ctx)
					.data(manga.coverUrl)
					.target(
						onSuccess = { image ->
							if (current?.id == manga.id) {
								cover.setImageDrawable(image.asDrawable(ctx.resources))
							}
						},
						onError = {
							if (current?.id == manga.id) {
								cover.setImageResource(R.drawable.ic_widget_cover_placeholder)
							}
						},
					)
					.crossfade(true)
					.mangaExtra(manga)
					.enqueueWith(imageLoader)
				bindSelectionOnly(manga)
			}

			fun bindSelectionOnly(manga: Manga) {
				val isSelected = selected.contains(manga.id)
				check.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
				itemView.isActivated = isSelected
			}
		}

		companion object {
			private const val PAYLOAD_SELECTION = "selection"
		}
	}

	companion object {
		const val EXTRA_RECONFIGURE = "is_reconfigure"
	}
}
