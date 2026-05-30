package org.koitharu.kotatsu.settings.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Window-Y based scroll request used by settings-search highlighting. The scaffold provides an
 * implementation; a highlighted [SettingsItem] passes its own window Y so the scaffold scrolls it
 * comfortably into view. (We do NOT use bringIntoViewRequester — it proved unreliable here.)
 */
val LocalSettingsHighlightScroll = compositionLocalOf<(Float) -> Unit> { {} }

/**
 * Top-level container for every redesigned settings screen.
 *
 * Uses a plain scrolling [Column] (not a LazyColumn) so EVERY row is composed and laid out even
 * when off-screen — that is what lets settings-search reliably scroll to and highlight an option
 * anywhere on the page. Settings screens are short enough that non-lazy composition is fine.
 */
@Composable
fun SettingsScaffold(
	@Suppress("UNUSED_PARAMETER") title: String,
	@Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
	modifier: Modifier = Modifier,
	content: SettingsListScope.() -> Unit,
) {
	val scope = SettingsListScopeImpl()
	scope.content()

	val scrollState = rememberScrollState()
	val coroutineScope = rememberCoroutineScope()
	val viewportTop = remember { mutableFloatStateOf(0f) }
	val viewportHeight = remember { mutableIntStateOf(0) }
	val scrollTo = remember(scrollState, coroutineScope) {
		{ windowY: Float ->
			val bias = viewportHeight.intValue * 0.28f
			val target = (scrollState.value + (windowY - viewportTop.floatValue) - bias)
				.toInt()
				.coerceIn(0, scrollState.maxValue)
			coroutineScope.launch { scrollState.animateScrollTo(target) }
			Unit
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.onGloballyPositioned {
				viewportTop.floatValue = it.positionInWindow().y
				viewportHeight.intValue = it.size.height
			},
	) {
		CompositionLocalProvider(LocalSettingsHighlightScroll provides scrollTo) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
			) {
				scope.items.forEach { item ->
					Box(Modifier.fillMaxWidth()) { item() }
				}
			}
		}
	}
}

/** Minimal builder mirroring the old `LazyListScope.item { }` call sites used across screens. */
interface SettingsListScope {
	fun item(content: @Composable () -> Unit)
}

private class SettingsListScopeImpl : SettingsListScope {
	val items = mutableListOf<@Composable () -> Unit>()
	override fun item(content: @Composable () -> Unit) {
		items += content
	}
}
