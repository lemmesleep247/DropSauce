package org.koitharu.kotatsu.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderBlackScreenRegressionTest {

	@Test
	fun `foreground captcha handling remains automatic by default`() {
		val source = source("org/koitharu/kotatsu/core/exceptions/resolve/CaptchaHandler.kt")
		val handleFunction = source
			.substringAfter("@CheckResultsuspendfunhandle(")
			.substringBefore("suspendfundiscard(")

		assertTrue(handleFunction.startsWith("exception:CloudFlareException,tryAutoResolve:Boolean=true,):Boolean="))
		assertTrue(handleFunction.contains("tryAutoResolve=tryAutoResolve"))
	}

	@Test
	fun `tracking worker never starts automatic captcha resolution`() {
		val source = source("org/koitharu/kotatsu/tracker/work/TrackWorker.kt")

		assertEquals(listOf("e,tryAutoResolve=false"), captchaHandlerArguments(source))
	}

	@Test
	fun `suggestions worker never starts automatic captcha resolution`() {
		val source = source("org/koitharu/kotatsu/suggestions/ui/SuggestionsWorker.kt")

		assertEquals(listOf("e,tryAutoResolve=false"), captchaHandlerArguments(source))
	}

	@Test
	fun `browser removes webview before destroying it`() {
		val source = source("org/koitharu/kotatsu/browser/BaseBrowserActivity.kt")
		val onDestroy = source
			.substringAfter("overridefunonDestroy(){")
			.substringBefore("openfunonLoadingStateChanged(")
		val removeCall = "(viewBinding.webView.parentas?ViewGroup)?.removeView(viewBinding.webView)"
		val destroyCall = "viewBinding.webView.destroy()"
		val superCall = "super.onDestroy()"
		val removeIndex = onDestroy.indexOf(removeCall)
		val destroyIndex = onDestroy.indexOf(destroyCall)
		val superIndex = onDestroy.indexOf(superCall)

		assertEquals(1, onDestroy.windowed(removeCall.length).count { it == removeCall })
		assertEquals(1, onDestroy.windowed(destroyCall.length).count { it == destroyCall })
		assertEquals(1, onDestroy.windowed(superCall.length).count { it == superCall })
		assertTrue(removeIndex >= 0)
		assertTrue(removeIndex < destroyIndex)
		assertTrue(destroyIndex < superIndex)
	}

	private fun captchaHandlerArguments(source: String): List<String> {
		return Regex("""captchaHandler\.handle\(([^)]*)\)""")
			.findAll(source)
			.map { it.groupValues[1] }
			.toList()
	}

	private fun source(relativePath: String): String {
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
