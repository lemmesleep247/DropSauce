package org.koitharu.kotatsu.extensions.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalExtensionRepoNameTest {

	@Test
	fun `github raw url shows user and repo`() {
		assertEquals(
			"keiyoushi/extensions",
			getExternalExtensionRepoDisplayName("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"),
		)
		assertEquals(
			"keiyoushi/extensions",
			getExternalExtensionRepoDisplayName("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"),
		)
	}

	@Test
	fun `github pages url shows user and repo`() {
		assertEquals(
			"someone/my-repo",
			getExternalExtensionRepoDisplayName("https://someone.github.io/my-repo/index.min.json"),
		)
	}

	@Test
	fun `generic host falls back to host and first path segment`() {
		assertEquals(
			"example.com/myrepo",
			getExternalExtensionRepoDisplayName("https://example.com/myrepo/index.min.json"),
		)
	}

	@Test
	fun `bare host falls back to host`() {
		assertEquals("example.com", getExternalExtensionRepoDisplayName("https://example.com/index.min.json"))
	}
}
