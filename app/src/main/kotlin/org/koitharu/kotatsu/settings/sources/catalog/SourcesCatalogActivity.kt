package org.koitharu.kotatsu.settings.sources.catalog

import android.annotation.SuppressLint
import android.os.Bundle
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.ui.widgets.ChipsView.ChipModel
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.ContentType
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SourcesCatalogActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	ExtensionActionListener,
	AppBarOwner,
	ListHeaderClickListener,
	MenuItem.OnActionExpandListener,
	ChipsView.OnChipClickListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val isExternalOnly by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, false) == true
	}
	private var isScrollToTopShown = false
	private var hasPendingUpdates = false
	private var lastSystemBarsInsets = Insets.NONE
	private val downloadManager by lazy(LazyThreadSafetyMode.NONE) {
		getSystemService(DOWNLOAD_SERVICE) as DownloadManager
	}
	private val pendingInstallQueue = ArrayDeque<SourcesCatalogViewModel.InstallRequest>()
	private val pendingInstallerDownloads = HashSet<Long>()
	private val pendingDownloadedInstalls = ArrayDeque<Long>()
	private val downloadRequestsById = HashMap<Long, PendingDownload>()
	private var activeInstallerPackage: String? = null
	private var activeInstallerDownloadId = 0L
	private var isInstallerActive = false
	private var pendingUninstallPackage: String? = null
	private val appBarOffsetListener = AppBarLayout.OnOffsetChangedListener { _, _ ->
		syncFastScrollerOffset()
	}
	private val extensionDownloadReceiver = ExtensionDownloadReceiver { downloadId ->
		if (pendingInstallerDownloads.remove(downloadId)) {
			if (isDownloadSuccessful(downloadId)) {
				pendingDownloadedInstalls += downloadId
			} else {
				viewModel.clearExtensionInProgress(downloadRequestsById.remove(downloadId)?.packageName)
				removeDownloadedApk(downloadId)
			}
			settings.pendingExtensionDownloads = pendingInstallerDownloads
			processDownloadedInstallerQueue()
		}
	}
	private val storagePermissionRequest = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { granted ->
		if (granted) {
			processInstallQueue()
		}
	}
	private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (!canInstallPackages()) {
			Toast.makeText(this, R.string.extension_install_permission_required_message, Toast.LENGTH_LONG).show()
			closeExtensionManagerToExplore()
			return@registerForActivityResult
		}
		processInstallQueue()
		processDownloadedInstallerQueue()
	}
	private val packageInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		finishActiveInstaller(refresh = true)
		processDownloadedInstallerQueue()
	}
	private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		viewModel.clearExtensionInProgress(pendingUninstallPackage)
		pendingUninstallPackage = null
		viewModel.refresh()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		clearOldApks()
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		if (isExternalOnly) {
			title = getString(R.string.extension_management)
		}
		val sourcesAdapter = SourcesCatalogAdapter(extensionActionListener = this, headerClickListener = this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = sourcesAdapter
			fastScroller.setTrackTouchEnabled(false)
		}
		viewBinding.appbar.addOnOffsetChangedListener(appBarOffsetListener)
		viewBinding.recyclerView.doOnLayout {
			syncFastScrollerOffset()
		}
		viewBinding.chipsFilter.onChipClickListener = this
		pendingInstallerDownloads.addAll(settings.pendingExtensionDownloads)
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, sourcesAdapter)
		viewModel.hasUpdates.observe(this) { hasUpdates ->
			hasPendingUpdates = hasUpdates
			applyRecyclerPadding(lastSystemBarsInsets)
		}
		viewModel.isRefreshing.observe(this) {
			viewBinding.swipeRefreshLayout.isRefreshing = it
		}
		viewBinding.swipeRefreshLayout.setOnRefreshListener {
			viewModel.refresh()
		}
		viewModel.onOpenPackageInstaller.observeEvent(this) { requests ->
			for (request in requests) {
				enqueueInstall(request)
			}
		}
		viewModel.onOpenUninstall.observeEvent(this) { pkg ->
			val uri = Uri.fromParts("package", pkg, null)
			val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				Intent.ACTION_DELETE
			} else {
				@Suppress("DEPRECATION")
				Intent.ACTION_UNINSTALL_PACKAGE
			}
			val intent = Intent(action, uri)
			try {
				pendingUninstallPackage = pkg
				viewModel.setExtensionInProgress(pkg, true)
				uninstallLauncher.launch(intent)
			} catch (_: ActivityNotFoundException) {
				pendingUninstallPackage = null
				viewModel.clearExtensionInProgress(pkg)
				Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			}
		}
		viewModel.onShowMessage.observeEvent(this) { msg ->
			Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
		}
		combine(
			viewModel.appliedFilter,
			viewModel.hasNewSources,
			viewModel.contentTypes,
			viewModel.locales,
			viewModel.isNsfwDisabled,
		) { filter, hasNewSources, contentTypes, locales, isNsfwDisabled ->
			CatalogUiState(filter, hasNewSources, contentTypes, locales, isNsfwDisabled)
		}.observe(this) {
			updateFilers(it.filter, it.hasNewSources, it.contentTypes, it.locales, it.isNsfwDisabled)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this, isExternalOnly))
		ContextCompat.registerReceiver(
			this,
			extensionDownloadReceiver,
			IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)
		checkPendingInstallerDownloads()
		ensureInstallPermissionAccess()
		handleAddRepoDeepLink(intent)
		viewBinding.buttonScrollToTop.setOnClickListener {
			viewBinding.recyclerView.smoothScrollToTop()
		}
		viewBinding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
				super.onScrolled(recyclerView, dx, dy)
				updateScrollToTopVisibility()
			}
		})
		updateScrollToTopVisibility()
	}

	override fun onDestroy() {
		viewBinding.appbar.removeOnOffsetChangedListener(appBarOffsetListener)
		unregisterReceiver(extensionDownloadReceiver)
		clearOldApks()
		super.onDestroy()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		lastSystemBarsInsets = bars
		applyRecyclerPadding(bars)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		viewBinding.buttonScrollToTop.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
			leftMargin = bars.left
			rightMargin = bars.right
			bottomMargin = bars.bottom + resources.getDimensionPixelOffset(R.dimen.margin_normal)
		}
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is ContentType -> viewModel.setContentType(data, !chip.isChecked)
			FilterChip.NEW_ONLY -> viewModel.setNewOnly(!chip.isChecked)
			FilterChip.NSFW_DISABLED -> viewModel.setNsfwDisabled(!chip.isChecked)
			FilterChip.LOCALE -> showLocalesMenu(chip)
			else -> Unit
		}
	}

	override fun onExtensionActionClick(item: SourceCatalogItem.Extension) {
		viewModel.onInstallEntryClick(item)
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		if (item.payload == SourcesCatalogViewModel.HEADER_ACTION_UPDATE_ALL) {
			viewModel.updateAllExtensions()
		}
	}

	override fun onExtensionSettingsClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openSourceSettings(MangaSource(sourceName))
	}

	override fun onExtensionItemClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openList(MangaSource(sourceName), null, null)
	}

	override fun onExtensionHideClick(item: SourceCatalogItem.Extension) {
		viewModel.setExtensionHidden(item.packageName, !item.isHidden)
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		val sq = (item.actionView as? SearchView)?.query?.trim()?.toString().orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		viewModel.performSearch(null)
		return true
	}

	private fun updateFilers(
		appliedFilter: SourcesCatalogFilter,
		hasNewSources: Boolean,
		contentTypes: List<ContentType>,
		locales: Set<String?>,
		isNsfwDisabled: Boolean,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 3)
		if (locales.size > 1) {
			chips += ChipModel(
				title = appliedFilter.locale?.toLocale().getDisplayName(this),
				icon = R.drawable.ic_language,
				isDropdown = true,
				data = FilterChip.LOCALE,
			)
		}
		chips += ChipModel(
			title = getString(R.string.disable_nsfw),
			icon = R.drawable.ic_nsfw,
			isChecked = isNsfwDisabled,
			data = FilterChip.NSFW_DISABLED,
		)
		if (hasNewSources) {
			chips += ChipModel(
				title = getString(R.string._new),
				icon = R.drawable.ic_updated,
				isChecked = appliedFilter.isNewOnly,
				data = FilterChip.NEW_ONLY,
			)
		}
		contentTypes.mapTo(chips) { type ->
			ChipModel(
				title = getString(type.titleResId),
				isChecked = type in appliedFilter.types,
				data = type,
			)
		}
		viewBinding.chipsFilter.setChips(chips)
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.locales.value.mapTo(ArrayList(viewModel.locales.value.size)) {
			it to it?.toLocale()
		}
		locales.sortWith(compareBy(nullsFirst(LocaleComparator())) { it.second })
		val menu = PopupMenu(this, anchor)
		for ((i, lc) in locales.withIndex()) {
			menu.menu.add(Menu.NONE, Menu.NONE, i, lc.second.getDisplayName(this))
		}
		menu.setOnMenuItemClickListener {
			viewModel.setLocale(locales.getOrNull(it.order)?.first)
			true
		}
		menu.show()
	}

	fun onManageRepoRequested() {
		val hasRepo = viewModel.hasExternalRepoConfigured()
		val dialogBuilder = MaterialAlertDialogBuilder(this)
			.setTitle(if (hasRepo) R.string.change_repo else R.string.add_repo)
		val editor = dialogBuilder.setEditText(
			inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
			singleLine = true,
		)
		editor.setText(viewModel.getExternalRepoUrl().orEmpty())
		editor.hint = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
		if (hasRepo) {
			dialogBuilder.setNeutralButton(R.string.remove_repo) { _, _ ->
				onRemoveRepoRequested()
			}
			dialogBuilder.setNegativeButton(android.R.string.cancel, null)
		} else {
			dialogBuilder.setNegativeButton(android.R.string.cancel, null)
		}
		dialogBuilder.setPositiveButton(android.R.string.ok, null)
		val dialog = dialogBuilder.create()
		dialog.setOnShowListener {
			val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val value = editor.text?.toString()?.trim().orEmpty()
				if (!value.startsWith("https://")) {
					editor.error = getString(R.string.invalid_url)
					return@setOnClickListener
				}
				viewModel.setExternalRepoUrl(value)
				dialog.dismiss()
			}
			val neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
			val defaultColor = neutralButton?.currentTextColor ?: positiveButton.currentTextColor
			val redColor = ContextCompat.getColor(dialog.context, android.R.color.holo_red_dark)
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(
				ColorUtils.blendARGB(defaultColor, redColor, 0.5f),
			)
		}
		dialog.show()
	}

	fun onRemoveRepoRequested() {
		viewModel.setExternalRepoUrl(null)
	}

	private fun handleAddRepoDeepLink(intent: Intent?) {
		if (intent?.data?.host != "add-repo") return
		if (intent.scheme != "kotatsu" && intent.scheme != "tachiyomi") return
		val url = intent.data?.getQueryParameter("url")?.trim() ?: return
		if (url.isBlank()) return
		if (!url.startsWith("https://")) {
			Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
			return
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.add_repo)
			.setMessage(getString(R.string.add_repo_confirmation, url))
			.setPositiveButton(android.R.string.ok) { _, _ ->
				viewModel.setExternalRepoUrl(url)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private data class CatalogUiState(
		val filter: SourcesCatalogFilter,
		val hasNewSources: Boolean,
		val contentTypes: List<ContentType>,
		val locales: Set<String?>,
		val isNsfwDisabled: Boolean,
	)

	private enum class FilterChip {
		NEW_ONLY,
		LOCALE,
		NSFW_DISABLED,
	}

	private fun updateScrollToTopVisibility() {
		val layoutManager = viewBinding.recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
		val shouldShow = layoutManager.findFirstVisibleItemPosition() >= 6
		if (shouldShow == isScrollToTopShown) {
			return
		}
		isScrollToTopShown = shouldShow
		viewBinding.buttonScrollToTop.animate().cancel()
		if (shouldShow) {
			viewBinding.buttonScrollToTop.alpha = 0f
			viewBinding.buttonScrollToTop.visibility = View.VISIBLE
			viewBinding.buttonScrollToTop.animate()
				.alpha(1f)
				.setDuration(160L)
				.start()
		} else {
			viewBinding.buttonScrollToTop.animate()
				.alpha(0f)
				.setDuration(160L)
				.withEndAction {
					viewBinding.buttonScrollToTop.visibility = View.GONE
				}
				.start()
		}
	}

	private fun syncFastScrollerOffset() {
		val fastScroller = viewBinding.recyclerView.fastScroller
		val baseTopMargin = resources.getDimensionPixelOffset(R.dimen.fastscroll_scrollbar_margin_top)
		val appBarBottom = viewBinding.appbar.bottom
		val layoutParams = fastScroller.layoutParams
		if (layoutParams is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
			val desiredTop = appBarBottom + baseTopMargin
			if (layoutParams.topMargin != desiredTop) {
				layoutParams.topMargin = desiredTop
				fastScroller.layoutParams = layoutParams
			}
		} else {
			fastScroller.translationY = appBarBottom.toFloat()
		}
	}

	private fun applyRecyclerPadding(systemBars: Insets) {
		val updatesTopPadding = if (hasPendingUpdates) {
			resources.getDimensionPixelOffset(R.dimen.margin_small)
		} else {
			0
		}
		viewBinding.recyclerView.updatePadding(
			left = systemBars.left,
			top = updatesTopPadding,
			right = systemBars.right,
			bottom = systemBars.bottom,
		)
	}

	private fun enqueueInstall(request: SourcesCatalogViewModel.InstallRequest) {
		pendingInstallQueue += request
		viewModel.setExtensionInProgress(request.packageName, true)
		processInstallQueue()
	}

	private fun processInstallQueue() {
		if (!canInstallPackages()) {
			requestInstallPackagesPermission()
			return
		}
		val nextRequest = pendingInstallQueue.removeFirstOrNull() ?: return
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			storagePermissionRequest.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
			pendingInstallQueue.addFirst(nextRequest)
		} else {
			downloadAndInstallExtension(nextRequest)
		}
	}

	private fun downloadAndInstallExtension(requestModel: SourcesCatalogViewModel.InstallRequest) {
		val uri = Uri.parse(requestModel.url)
		val fileName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "extension.apk"
		val request = DownloadManager.Request(uri)
			.setTitle(fileName)
			.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
			.setMimeType("application/vnd.android.package-archive")
		val downloadId = downloadManager.enqueue(request)
		downloadRequestsById[downloadId] = PendingDownload(
			packageName = requestModel.packageName,
			fileName = fileName,
		)
		pendingInstallerDownloads += downloadId
		settings.pendingExtensionDownloads = pendingInstallerDownloads
		Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
		processInstallQueue()
	}

	private fun installDownloadedApk(downloadId: Long) {
		if (!canInstallPackages()) {
			pendingDownloadedInstalls.addFirst(downloadId)
			requestInstallPackagesPermission()
			return
		}
		val pendingDownload = downloadRequestsById.remove(downloadId)
		activeInstallerPackage = pendingDownload?.packageName
		activeInstallerDownloadId = downloadId
		isInstallerActive = true
		val apkUri = getDownloadedApkUri(downloadId, pendingDownload?.fileName) ?: run {
			finishActiveInstaller(refresh = false)
			return
		}
		val installIntent = createInstallIntent(apkUri)
		try {
			grantInstallerUriPermissions(installIntent, apkUri)
			packageInstallerLauncher.launch(installIntent)
		} catch (_: ActivityNotFoundException) {
			isInstallerActive = false
			viewModel.clearExtensionInProgress(activeInstallerPackage)
			removeDownloadedApk(activeInstallerDownloadId)
			activeInstallerPackage = null
			activeInstallerDownloadId = 0L
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		} catch (_: SecurityException) {
			isInstallerActive = false
			pendingDownload?.let { downloadRequestsById[downloadId] = it }
			pendingDownloadedInstalls.addFirst(downloadId)
			activeInstallerPackage = null
			activeInstallerDownloadId = 0L
			requestInstallPackagesPermission()
			Toast.makeText(this, R.string.extension_install_permission_required_message, Toast.LENGTH_LONG).show()
		}
	}

	@Suppress("DEPRECATION")
	@SuppressLint("RequestInstallPackagesPolicy")
	private fun createInstallIntent(apkUri: Uri): Intent {
		val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			.setDataAndType(apkUri, "application/vnd.android.package-archive")
			.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			.putExtra(Intent.EXTRA_RETURN_RESULT, true)
		findPackageInstallerPackage(installIntent)?.let(installIntent::setPackage)
		return installIntent
	}

	private fun getDownloadedApkUri(downloadId: Long, fileName: String?): Uri? {
		getDownloadedApkFile(downloadId, fileName)?.takeIf { it.isFile }?.let { file ->
			return FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
		}
		return downloadManager.getUriForDownloadedFile(downloadId)
	}

	private fun getDownloadedApkFile(downloadId: Long, fileName: String?): File? {
		val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
		if (downloadsDir != null && !fileName.isNullOrBlank()) {
			File(downloadsDir, fileName).takeIf { it.exists() }?.let { return it }
		}
		val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
		cursor.use {
			if (!it.moveToFirst()) {
				return null
			}
			val localUriColumn = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
			if (localUriColumn < 0) {
				return null
			}
			val localUri = it.getString(localUriColumn)?.takeIf { value -> value.isNotBlank() } ?: return null
			val uri = Uri.parse(localUri)
			return if (uri.scheme == "file") {
				uri.path?.let(::File)
			} else {
				null
			}
		}
	}

	@Suppress("DEPRECATION")
	private fun grantInstallerUriPermissions(installIntent: Intent, apkUri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
		for (resolveInfo in packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
			val targetPackage = resolveInfo.activityInfo?.packageName ?: continue
			grantUriPermission(targetPackage, apkUri, flags)
		}
	}

	@Suppress("DEPRECATION")
	private fun findPackageInstallerPackage(installIntent: Intent): String? {
		return packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
			.firstOrNull { resolveInfo ->
				val targetPackage = resolveInfo.activityInfo?.packageName ?: return@firstOrNull false
				targetPackage.contains("packageinstaller", ignoreCase = true) ||
					targetPackage.contains("package.installer", ignoreCase = true)
			}?.activityInfo?.packageName
	}

	private fun finishActiveInstaller(
		packageName: String? = activeInstallerPackage,
		downloadId: Long = activeInstallerDownloadId,
		refresh: Boolean,
	) {
		val isCurrentInstaller = activeInstallerDownloadId == 0L ||
			downloadId == 0L ||
			activeInstallerDownloadId == downloadId
		if (isCurrentInstaller) {
			isInstallerActive = false
		}
		viewModel.clearExtensionInProgress(packageName)
		removeDownloadedApk(downloadId)
		if (isCurrentInstaller) {
			activeInstallerPackage = null
			activeInstallerDownloadId = 0L
		}
		if (refresh) {
			viewModel.refresh()
		}
	}

	private fun removeDownloadedApk(downloadId: Long) {
		if (downloadId != 0L) {
			runCatching { downloadManager.remove(downloadId) }
		}
	}

	private fun checkPendingInstallerDownloads() {
		if (pendingInstallerDownloads.isEmpty()) {
			return
		}
		val pendingSnapshot = pendingInstallerDownloads.toLongArray()
		val query = DownloadManager.Query().setFilterById(*pendingSnapshot)
		val cursor = downloadManager.query(query) ?: return
		cursor.use {
			val idColumn = it.getColumnIndex(DownloadManager.COLUMN_ID)
			val statusColumn = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
			while (it.moveToNext()) {
				val id = if (idColumn >= 0) it.getLong(idColumn) else continue
				val status = if (statusColumn >= 0) it.getInt(statusColumn) else continue
				if (!pendingInstallerDownloads.remove(id)) {
					continue
				}
				if (status == DownloadManager.STATUS_SUCCESSFUL) {
					pendingDownloadedInstalls += id
				} else if (status == DownloadManager.STATUS_FAILED) {
					viewModel.clearExtensionInProgress(downloadRequestsById.remove(id)?.packageName)
					removeDownloadedApk(id)
				} else {
					pendingInstallerDownloads += id
				}
			}
		}
		settings.pendingExtensionDownloads = pendingInstallerDownloads
		processDownloadedInstallerQueue()
	}

	private fun isDownloadSuccessful(downloadId: Long): Boolean {
		val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return false
		cursor.use {
			if (!it.moveToFirst()) {
				return false
			}
			val statusColumn = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
			if (statusColumn < 0) {
				return false
			}
			return it.getInt(statusColumn) == DownloadManager.STATUS_SUCCESSFUL
		}
	}

	private fun processDownloadedInstallerQueue() {
		if (isInstallerActive) {
			return
		}
		while (!isInstallerActive) {
			val downloadId = pendingDownloadedInstalls.removeFirstOrNull() ?: break
			installDownloadedApk(downloadId)
		}
	}

	private fun canInstallPackages(): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return true
		}
		return packageManager.canRequestPackageInstalls()
	}

	private fun requestInstallPackagesPermission() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return
		}
		val intent = Intent(
			Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
			Uri.parse("package:$packageName"),
		)
		installPermissionLauncher.launch(intent)
	}

	private fun ensureInstallPermissionAccess() {
		if (canInstallPackages()) {
			return
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.extension_install_permission_required_title)
			.setMessage(R.string.extension_install_permission_required_message)
			.setCancelable(false)
			.setNegativeButton(android.R.string.cancel) { _, _ ->
				closeExtensionManagerToExplore()
			}
			.setPositiveButton(android.R.string.ok) { _, _ ->
				requestInstallPackagesPermission()
			}
			.show()
	}

	private fun closeExtensionManagerToExplore() {
		startActivity(
			AppRouter.homeIntent(this)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
		finish()
	}

	private fun clearOldApks() {
		try {
			val destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			destDir?.listFiles()?.forEach { file ->
				if (file.name.endsWith(".apk")) {
					file.delete()
				}
			}
		} catch (_: Exception) {
			// Ignore
		}
	}

	private data class PendingDownload(
		val packageName: String,
		val fileName: String,
	)

	private class ExtensionDownloadReceiver(
		private val onComplete: (Long) -> Unit,
	) : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
				val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
				if (downloadId != 0L) {
					onComplete(downloadId)
				}
			}
		}
	}
}
