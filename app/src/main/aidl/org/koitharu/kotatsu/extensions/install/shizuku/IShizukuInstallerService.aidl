package org.koitharu.kotatsu.extensions.install.shizuku;

import android.content.res.AssetFileDescriptor;

interface IShizukuInstallerService {
	void install(in AssetFileDescriptor apk) = 1;
	void destroy() = 16777114;
}
