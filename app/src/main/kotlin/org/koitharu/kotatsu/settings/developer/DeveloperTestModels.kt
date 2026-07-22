package org.koitharu.kotatsu.settings.developer

enum class DeveloperTestStageStatus {
	PASSED,
	SKIPPED,
	BLOCKED,
	FAILED,
}

enum class DeveloperExtensionStatus {
	NO_ISSUES,
	BLOCKED,
	ERROR,
}

data class DeveloperTestStageResult(
	val name: String,
	val status: DeveloperTestStageStatus,
	val message: String?,
	val durationMillis: Long,
)

data class DeveloperExtensionTestResult(
	val packageName: String,
	val extensionName: String,
	val sourceName: String,
	val language: String,
	val stages: List<DeveloperTestStageResult>,
	val durationMillis: Long,
) {
	val status: DeveloperExtensionStatus
		get() = stages.extensionStatus()
}

fun List<DeveloperTestStageResult>.extensionStatus(): DeveloperExtensionStatus = when {
	any { it.status == DeveloperTestStageStatus.FAILED } -> DeveloperExtensionStatus.ERROR
	any { it.status == DeveloperTestStageStatus.BLOCKED } -> DeveloperExtensionStatus.BLOCKED
	else -> DeveloperExtensionStatus.NO_ISSUES
}
