package org.koitharu.kotatsu.extensions.install.shizuku

import android.annotation.SuppressLint
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.SystemServiceHelper
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * Runs with Shizuku's shell identity. This class is instantiated as a Shizuku user service,
 * not as an Android manifest service.
 */
class ShizukuInstallerService : IShizukuInstallerService.Stub() {

	@SuppressLint("PrivateApi")
	override fun install(
		apk: AssetFileDescriptor,
		userId: Int,
		expectedPackage: String,
		installerPackage: String,
		statusReceiver: IntentSender,
	) {
		val packageManager = Class.forName("android.content.pm.IPackageManager\$Stub")
			.getMethod("asInterface", IBinder::class.java)
			.invoke(null, SystemServiceHelper.getSystemService("package"))
		val packageInstaller = Class.forName("android.content.pm.IPackageManager")
			.getMethod("getPackageInstaller")
			.invoke(packageManager)

		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			val installFlags = javaClass.getField("installFlags")
			installFlags.setInt(this, installFlags.getInt(this) or INSTALL_REPLACE_EXISTING)
			setAppPackageName(expectedPackage)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				setInstallerPackageName(installerPackage)
			}
		}

		val sessionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			packageInstaller.javaClass.getMethod(
				"createSession",
				PackageInstaller.SessionParams::class.java,
				String::class.java,
				String::class.java,
				Int::class.java,
			).invoke(packageInstaller, params, installerPackage, installerPackage, userId) as Int
		} else {
			packageInstaller.javaClass.getMethod(
				"createSession",
				PackageInstaller.SessionParams::class.java,
				String::class.java,
				Int::class.java,
			).invoke(packageInstaller, params, installerPackage, userId) as Int
		}

		val session = packageInstaller.javaClass
			.getMethod("openSession", Int::class.java)
			.invoke(packageInstaller, sessionId)
		var committed = false
		try {
			session.javaClass.getMethod(
				"openWrite",
				String::class.java,
				Long::class.java,
				Long::class.java,
			).invoke(session, "extension.apk", 0L, apk.length)
				.let { it as ParcelFileDescriptor }
				.toOutputStream()
				.use { output ->
					apk.createInputStream().use { input -> input.copyTo(output) }
				}

			if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
				session.javaClass.getMethod("commit", IntentSender::class.java, Boolean::class.java)
					.invoke(session, statusReceiver, false)
			} else {
				session.javaClass.getMethod("commit", IntentSender::class.java)
					.invoke(session, statusReceiver)
			}
			committed = true
		} finally {
			if (!committed) {
				runCatching { session.javaClass.getMethod("abandon").invoke(session) }
			}
			runCatching { session.javaClass.getMethod("close").invoke(session) }
		}
	}

	override fun destroy() = exitProcess(0)

	@SuppressLint("PrivateApi")
	private fun ParcelFileDescriptor.toOutputStream(): OutputStream {
		val revocable = Class.forName("android.os.SystemProperties")
			.getMethod("getBoolean", String::class.java, Boolean::class.java)
			.invoke(null, "fw.revocable_fd", false) as Boolean
		return if (revocable) {
			ParcelFileDescriptor.AutoCloseOutputStream(this)
		} else {
			Class.forName("android.os.FileBridge\$FileBridgeOutputStream")
				.getConstructor(ParcelFileDescriptor::class.java)
				.newInstance(this) as OutputStream
		}
	}
}

private const val INSTALL_REPLACE_EXISTING = 0x00000002
