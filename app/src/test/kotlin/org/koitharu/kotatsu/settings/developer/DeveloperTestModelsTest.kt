package org.koitharu.kotatsu.settings.developer

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperTestModelsTest {

	@Test
	fun `required failure makes extension an error`() {
		val stages = listOf(
			stage(DeveloperTestStageStatus.PASSED),
			stage(DeveloperTestStageStatus.FAILED),
		)

		assertEquals(DeveloperExtensionStatus.ERROR, stages.extensionStatus())
	}

	@Test
	fun `interactive failure makes extension blocked`() {
		val stages = listOf(
			stage(DeveloperTestStageStatus.PASSED),
			stage(DeveloperTestStageStatus.BLOCKED),
		)

		assertEquals(DeveloperExtensionStatus.BLOCKED, stages.extensionStatus())
	}

	@Test
	fun `skipped optional stage still has no issues`() {
		val stages = listOf(
			stage(DeveloperTestStageStatus.PASSED),
			stage(DeveloperTestStageStatus.SKIPPED),
		)

		assertEquals(DeveloperExtensionStatus.NO_ISSUES, stages.extensionStatus())
	}

	private fun stage(status: DeveloperTestStageStatus) = DeveloperTestStageResult(
		name = "stage",
		status = status,
		message = null,
		durationMillis = 0,
	)
}
