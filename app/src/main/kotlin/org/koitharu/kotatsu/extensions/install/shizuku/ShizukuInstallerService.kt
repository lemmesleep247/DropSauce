package org.koitharu.kotatsu.extensions.install.shizuku

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import org.koitharu.kotatsu.BuildConfig
import rikka.shizuku.SystemServiceHelper
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * Runs with Shizuku's shell identity. This class is instantiated as a Shizuku user service,
 * not as an Android manifest service.
 */
class ShizukuInstallerService : IShizukuInstallerService.Stub() {

	private val userId = UserHandle::class.java
		.getMethod("myUserId")
		.invoke(null) as Int
	private val packageName = BuildConfig.APPLICATION_ID
	private val context = createShellContext()

	@SuppressLint("PrivateApi")
	override fun install(apk: AssetFileDescriptor) {
		val packageManager = Class.forName("android.content.pm.IPackageManager\$Stub")
			.getMethod("asInterface", IBinder::class.java)
			.invoke(null, SystemServiceHelper.getSystemService("package"))
		val packageInstaller = Class.forName("android.content.pm.IPackageManager")
			.getMethod("getPackageInstaller")
			.invoke(packageManager)

		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			val installFlags = javaClass.getField("installFlags")
			installFlags.setInt(this, installFlags.getInt(this) or INSTALL_REPLACE_EXISTING)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				setInstallerPackageName(packageName)
			}
		}

		val sessionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			packageInstaller.javaClass.getMethod(
				"createSession",
				PackageInstaller.SessionParams::class.java,
				String::class.java,
				String::class.java,
				Int::class.java,
			).invoke(packageInstaller, params, packageName, packageName, userId) as Int
		} else {
			packageInstaller.javaClass.getMethod(
				"createSession",
				PackageInstaller.SessionParams::class.java,
				String::class.java,
				Int::class.java,
			).invoke(packageInstaller, params, packageName, userId) as Int
		}

		val session = packageInstaller.javaClass
			.getMethod("openSession", Int::class.java)
			.invoke(packageInstaller, sessionId)
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

		val statusIntent = PendingIntent.getBroadcast(
			context,
			0,
			Intent(ACTION_SHIZUKU_INSTALL_RESULT).setPackage(packageName),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
		)
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
			session.javaClass.getMethod("commit", IntentSender::class.java, Boolean::class.java)
				.invoke(session, statusIntent.intentSender, false)
		} else {
			session.javaClass.getMethod("commit", IntentSender::class.java)
				.invoke(session, statusIntent.intentSender)
		}
	}

	override fun destroy() = exitProcess(0)

	@SuppressLint("PrivateApi")
	private fun createShellContext(): Context {
		val activityThread = Class.forName("android.app.ActivityThread")
		val systemMain = activityThread.getMethod("systemMain").invoke(null)
		val systemContext = activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
		val shellUser = UserHandle::class.java
			.getConstructor(Int::class.java)
			.newInstance(userId)
		val shellContext = systemContext.javaClass.getMethod(
			"createPackageContextAsUser",
			String::class.java,
			Int::class.java,
			UserHandle::class.java,
		).invoke(
			systemContext,
			"com.android.shell",
			Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
			shellUser,
		) as Context
		return shellContext.createPackageContext("com.android.shell", 0)
	}

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

const val ACTION_SHIZUKU_INSTALL_RESULT = "${BuildConfig.APPLICATION_ID}.SHIZUKU_INSTALL_RESULT"

private const val INSTALL_REPLACE_EXISTING = 0x00000002
