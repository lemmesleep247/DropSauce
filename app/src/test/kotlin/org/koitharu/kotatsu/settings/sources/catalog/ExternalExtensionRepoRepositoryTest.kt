package org.koitharu.kotatsu.settings.sources.catalog

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import org.koitharu.kotatsu.mihon.model.MihonLoadResult

class ExternalExtensionRepoRepositoryTest {

	@Test
	fun `resolveApkUrl places apk in apk subdirectory relative to repo base`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
			apkName = "tachiyomi-all.ahottie-v1.4.2.apk",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-all.ahottie-v1.4.2.apk",
			resolved,
		)
	}

	@Test
	fun `resolveApkUrl works with index pb url`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
			apkName = "tachiyomi-all.ahottie-v1.4.2.apk",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-all.ahottie-v1.4.2.apk",
			resolved,
		)
	}

	@Test
	fun `resolveApkUrl works with base url without index json`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
			apkName = "tachiyomi-all.ahottie-v1.4.2.apk",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-all.ahottie-v1.4.2.apk",
			resolved,
		)
	}

	@Test
	fun `resolveApkUrl keeps absolute apk urls unchanged`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://example.com/index.min.json",
			apkName = "https://cdn.example.com/ext.apk",
		)
		assertEquals("https://cdn.example.com/ext.apk", resolved)
	}

	@Test
	fun `resolveIconUrl constructs icon url from package name`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveIconUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
			packageName = "eu.kanade.tachiyomi.extension.all.weebdex",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/icon/eu.kanade.tachiyomi.extension.all.weebdex.png",
			resolved,
		)
	}

	@Test
	fun `newer extension version code is an update`() {
		assertTrue(repoEntry(versionCode = 11, versionName = "1.4.1").isNewerThan(installed()))
		assertFalse(repoEntry(versionCode = 10, versionName = "1.4.1").isNewerThan(installed()))
	}

	@Test
	fun `newer source api version is an update even with same extension version code`() {
		assertTrue(repoEntry(versionCode = 10, versionName = "1.5.0").isNewerThan(installed()))
	}

	@Test
	fun `loaded extension update detection uses the same version rules`() {
		val loaded = MihonLoadResult.Success(
			pkgName = "example.extension",
			appName = "Example",
			versionCode = 10,
			versionName = "1.4.1",
			libVersion = 1.4,
			lang = "en",
			isNsfw = false,
			sources = emptyList(),
		)
		assertTrue(repoEntry(versionCode = 10, versionName = "1.5.0").isNewerThan(loaded))
		assertFalse(repoEntry(versionCode = 10, versionName = "1.4.1").isNewerThan(loaded))
	}

	private fun repoEntry(versionCode: Long, versionName: String) = ExternalExtensionRepoEntry(
		name = "Example",
		packageName = "example.extension",
		apkName = "example.apk",
		versionCode = versionCode,
		versionName = versionName,
	)

	private fun installed() = MihonExtensionInfo(
		pkgName = "example.extension",
		appName = "Example",
		versionCode = 10,
		versionName = "1.4.1",
		libVersion = 1.4,
		lang = "en",
		isNsfw = false,
		sourceClassName = "ExampleSource",
		apkPath = "/example.apk",
	)
}
