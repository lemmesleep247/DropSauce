package org.koitharu.kotatsu.extensions.install.shizuku;

import android.content.IntentSender;
import android.content.res.AssetFileDescriptor;

interface IShizukuInstallerService {
	void install(
		in AssetFileDescriptor apk,
		int userId,
		String expectedPackage,
		String installerPackage,
		in IntentSender statusReceiver
	) = 1;
	void destroy() = 16777114;
}
