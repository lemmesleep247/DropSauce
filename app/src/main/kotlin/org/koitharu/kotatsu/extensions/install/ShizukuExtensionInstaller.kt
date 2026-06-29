package org.koitharu.kotatsu.extensions.install

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.extensions.install.shizuku.ACTION_SHIZUKU_INSTALL_RESULT
import org.koitharu.kotatsu.extensions.install.shizuku.IShizukuInstallerService
import org.koitharu.kotatsu.extensions.install.shizuku.ShizukuInstallerService
import rikka.shizuku.Shizuku
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ShizukuExtensionInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val installMutex = Mutex()

	val isInstalled: Boolean
		get() = runCatching {
			context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
			true
		}.getOrDefault(false)

	val isReady: Boolean
		get() = runCatching {
			Shizuku.pingBinder() &&
				Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
		}.getOrDefault(false)

	suspend fun install(apk: File, expectedPackage: String): InstallResult = installMutex.withLock {
		withContext(Dispatchers.IO) {
			if (!isReady) {
				return@withContext InstallResult.Unavailable
			}
			val actualPackage = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
			if (actualPackage == null || actualPackage != expectedPackage) {
				return@withContext InstallResult.InvalidPackage
			}

			val args = createUserServiceArgs()
			var connection: ServiceConnection? = null
			try {
				val bound = withTimeout(BIND_TIMEOUT_MS) {
					suspendCancellableCoroutine { continuation ->
						val callback = object : ServiceConnection {
							override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
								if (continuation.isActive) {
									continuation.resume(IShizukuInstallerService.Stub.asInterface(binder))
								}
							}

							override fun onServiceDisconnected(name: ComponentName?) = Unit
						}
						connection = callback
						continuation.invokeOnCancellation {
							runCatching { Shizuku.unbindUserService(args, callback, true) }
						}
						Shizuku.bindUserService(args, callback)
					}
				}
				awaitInstallResult {
					ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
						AssetFileDescriptor(descriptor, 0, apk.length()).use(bound::install)
					}
				}
			} catch (e: Exception) {
				InstallResult.Failure(e.message)
			} finally {
				connection?.let { callback ->
					runCatching {
						if (Shizuku.pingBinder()) {
							Shizuku.unbindUserService(args, callback, true)
						}
					}
				}
			}
		}
	}

	private suspend fun awaitInstallResult(start: () -> Unit): InstallResult = withTimeout(INSTALL_TIMEOUT_MS) {
		suspendCancellableCoroutine { continuation ->
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent) {
					if (!continuation.isActive) return
					runCatching { this@ShizukuExtensionInstaller.context.unregisterReceiver(this) }
					val status = intent.getIntExtra(
						PackageInstaller.EXTRA_STATUS,
						PackageInstaller.STATUS_FAILURE,
					)
					val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
					continuation.resume(
						if (status == PackageInstaller.STATUS_SUCCESS) {
							InstallResult.Success
						} else {
							InstallResult.Failure(message)
						},
					)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter(ACTION_SHIZUKU_INSTALL_RESULT),
				ContextCompat.RECEIVER_NOT_EXPORTED,
			)
			continuation.invokeOnCancellation {
				runCatching { context.unregisterReceiver(receiver) }
			}
			try {
				start()
			} catch (e: Exception) {
				runCatching { context.unregisterReceiver(receiver) }
				if (continuation.isActive) continuation.resume(InstallResult.Failure(e.message))
			}
		}
	}

	private fun createUserServiceArgs() = Shizuku.UserServiceArgs(
		ComponentName(context, ShizukuInstallerService::class.java),
	)
		.tag(USER_SERVICE_TAG)
		.processNameSuffix(USER_SERVICE_TAG)
		.debuggable(BuildConfig.DEBUG)
		.daemon(false)

	sealed interface InstallResult {
		data object Success : InstallResult
		data object Unavailable : InstallResult
		data object InvalidPackage : InstallResult
		data class Failure(val message: String?) : InstallResult
	}

	private companion object {
		const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
		const val USER_SERVICE_TAG = "extension_installer"
		const val BIND_TIMEOUT_MS = 15_000L
		const val INSTALL_TIMEOUT_MS = 180_000L
	}
}
