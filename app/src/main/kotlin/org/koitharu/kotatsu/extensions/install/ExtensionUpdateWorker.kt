package org.koitharu.kotatsu.extensions.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoRepository
import org.koitharu.kotatsu.settings.sources.catalog.isNewerThan
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class ExtensionUpdateWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val settings: AppSettings,
	private val repoRepository: ExternalExtensionRepoRepository,
	private val extensionLoader: MihonExtensionLoader,
	private val extensionManager: MihonExtensionManager,
	private val shizukuInstaller: ShizukuExtensionInstaller,
	@BaseHttpClient private val httpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		if (!settings.isAutoUpdateExtensionsEnabled || !settings.isShizukuInstallerEnabled) {
			return@withContext Result.success()
		}
		if (!shizukuInstaller.awaitReady()) {
			return@withContext Result.retry()
		}
		val repoUrl = settings.externalExtensionsRepoUrl ?: return@withContext Result.success()

		try {
			val installed = extensionLoader.getInstalledExtensions(applicationContext)
				.associateBy { it.pkgName }
			val updates = repoRepository.getExtensions(repoUrl, forceRefresh = true)
				.filter { entry ->
					installed[entry.packageName]?.let(entry::isNewerThan) == true
				}
				.sortedBy { it.name.lowercase() }
			if (updates.isEmpty()) return@withContext Result.success()

			val downloadDir = File(applicationContext.cacheDir, "extension_updates").apply { mkdirs() }
			var installedAny = false
			var retryNeeded = false
			var permanentFailure = false
			for (entry in updates) {
				if (isStopped) break
				val apk = File(downloadDir, "${entry.packageName}-${entry.versionCode}.apk")
				try {
					download(repoRepository.resolveApkUrl(repoUrl, entry.apkName), apk)
					when (val installResult = shizukuInstaller.install(apk, entry.packageName)) {
						ShizukuExtensionInstaller.InstallResult.Success -> installedAny = true
						ShizukuExtensionInstaller.InstallResult.Unavailable -> {
							retryNeeded = true
							break
						}
						ShizukuExtensionInstaller.InstallResult.InvalidPackage -> {
							permanentFailure = true
							Log.e(TAG, "Downloaded APK has the wrong package for ${entry.packageName}")
						}
						is ShizukuExtensionInstaller.InstallResult.Failure -> {
							Log.e(TAG, "Failed to update ${entry.packageName}: ${installResult.message}")
							if (
								installResult.status == null ||
								installResult.status == PackageInstaller.STATUS_FAILURE_TIMEOUT
							) {
								retryNeeded = true
							} else {
								permanentFailure = true
							}
						}
					}
				} finally {
					apk.delete()
				}
			}
			if (installedAny) extensionManager.loadExtensions()
			when {
				isStopped -> Result.retry()
				retryNeeded -> Result.retry()
				permanentFailure && !installedAny -> Result.failure()
				else -> Result.success()
			}
		} catch (_: IOException) {
			Result.retry()
		} catch (e: Exception) {
			Log.e(TAG, "Extension auto-update failed", e)
			Result.failure()
		}
	}

	private fun download(url: String, destination: File) {
		val request = Request.Builder().url(url).get().build()
		httpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			val body = response.body
			val length = body.contentLength()
			if (length > MAX_APK_BYTES) throw IOException("Extension APK is too large")
			body.byteStream().use { input ->
				destination.outputStream().buffered().use { output ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var total = 0L
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						total += count
						if (total > MAX_APK_BYTES) throw IOException("Extension APK is too large")
						output.write(buffer, 0, count)
					}
				}
			}
		}
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(1, TimeUnit.DAYS)
				.setConstraints(periodicConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(
				PERIODIC_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(PERIODIC_WORK_NAME).await()
			workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME).await()
		}

		override suspend fun isScheduled(): Boolean = workManager
			.awaitUniqueWorkInfoByName(PERIODIC_WORK_NAME)
			.any { !it.state.isFinished }

		suspend fun startNow() {
			val request = OneTimeWorkRequestBuilder<ExtensionUpdateWorker>()
				.setConstraints(immediateConstraints())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniqueWork(
				IMMEDIATE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				request,
			).await()
		}

		private fun immediateConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.build()

		private fun periodicConstraints() = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.CONNECTED)
			.setRequiresBatteryNotLow(true)
			.build()
	}

	private companion object {
		const val TAG = "ExtensionUpdateWorker"
		const val PERIODIC_WORK_NAME = "extension_auto_updates"
		const val IMMEDIATE_WORK_NAME = "extension_auto_updates_now"
		const val MAX_APK_BYTES = 100L * 1024L * 1024L
	}
}
