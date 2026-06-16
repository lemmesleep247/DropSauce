package org.koitharu.kotatsu.main.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.settings.compose.ColorSchemePickerRow

private const val PAGE_COUNT = 4
private val CARD_SHAPE = RoundedCornerShape(24.dp)
private val SCREEN_PADDING = 20.dp

data class OnboardingPermissions(
    val hasInstall: Boolean,
    val hasNotifications: Boolean,
    val hasBattery: Boolean,
)

data class OnboardingActions(
    val onThemeChange: (Int) -> Unit,
    val onColorSchemeChange: (String) -> Unit,
    val onAmoledChange: (Boolean) -> Unit,
    val onAmoledReset: () -> Unit,
    val onSelectDestination: () -> Unit,
    val onPermissionInstall: () -> Unit,
    val onPermissionNotifications: () -> Unit,
    val onPermissionBattery: () -> Unit,
    val onSignInGoogle: () -> Unit,
    val onRestoreDropSauce: () -> Unit,
    val onRestoreTachiyomi: () -> Unit,
    val onOpenGithub: () -> Unit,
    val onOpenDiscord: () -> Unit,
    val onVisitWebsite: () -> Unit,
    val onFinish: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    isAmoledEnabled: Boolean,
    storageSummary: String?,
    isLoading: Boolean,
    permissions: OnboardingPermissions,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    actions: OnboardingActions,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage
    val isLastPage = currentPage == PAGE_COUNT - 1

    BackHandler(enabled = currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp + bottomPadding),
        ) { pageIndex ->
            val iconRes = when (pageIndex) {
                0 -> R.drawable.ic_welcome
                1 -> R.drawable.ic_storage
                2 -> R.drawable.ic_sync
                else -> R.drawable.ic_save_ok
            }
            val titleRes = when (pageIndex) {
                0 -> R.string.welcome
                1 -> R.string.onboarding_storage_permissions_title
                2 -> R.string.onboarding_sync_title
                else -> R.string.onboarding_finish_title
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = topPadding + 32.dp,
                        start = SCREEN_PADDING,
                        end = SCREEN_PADDING,
                        bottom = 24.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(96.dp),
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(22.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(20.dp))
                when (pageIndex) {
                    0 -> WelcomeSlide(
                        selectedTheme = selectedTheme,
                        selectedColorScheme = selectedColorScheme,
                        isAmoledEnabled = isAmoledEnabled,
                        actions = actions,
                    )
                    1 -> StorageSlide(
                        storageSummary = storageSummary,
                        permissions = permissions,
                        actions = actions,
                    )
                    2 -> SyncSlide(isLoading = isLoading, actions = actions)
                    else -> FinishSlide(actions = actions)
                }
            }
        }

        // Bottom row: animated dot indicator + FAB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(
                    start = SCREEN_PADDING,
                    end = SCREEN_PADDING,
                    bottom = 20.dp + bottomPadding,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(PAGE_COUNT) { index ->
                    val isSelected = index == currentPage
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 24.dp else 8.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "dot_$index",
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                        label = "dot_color_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = dotWidth, height = 8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }
            FloatingActionButton(
                onClick = {
                    if (isLastPage) {
                        actions.onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    painter = painterResource(
                        if (isLastPage) R.drawable.ic_check else R.drawable.ic_arrow_forward,
                    ),
                    contentDescription = stringResource(
                        if (isLastPage) R.string.confirm else R.string.next,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeSlide(
    selectedTheme: Int,
    selectedColorScheme: ColorScheme,
    isAmoledEnabled: Boolean,
    actions: OnboardingActions,
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDarkEnabled = when (selectedTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> isSystemDark
    }

    // Mirror original: clear AMOLED pref when we land in light mode
    LaunchedEffect(isDarkEnabled) {
        if (!isDarkEnabled && isAmoledEnabled) {
            actions.onAmoledReset()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Color scheme picker (has its own surfaceContainer card)
        ColorSchemePickerRow(
            title = stringResource(R.string.color_theme),
            selectedValue = selectedColorScheme.name,
            onValueChange = { actions.onColorSchemeChange(it) },
        )

        // Appearance card: theme toggle + AMOLED switch
        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.theme),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedTheme == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                        onClick = { actions.onThemeChange(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        label = { Text(stringResource(R.string.follow_system)) },
                    )
                    SegmentedButton(
                        selected = selectedTheme == AppCompatDelegate.MODE_NIGHT_NO,
                        onClick = { actions.onThemeChange(AppCompatDelegate.MODE_NIGHT_NO) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        label = { Text(stringResource(R.string.light)) },
                    )
                    SegmentedButton(
                        selected = selectedTheme == AppCompatDelegate.MODE_NIGHT_YES,
                        onClick = { actions.onThemeChange(AppCompatDelegate.MODE_NIGHT_YES) },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        label = { Text(stringResource(R.string.dark)) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                // AMOLED row
                val contentAlpha = if (isDarkEnabled) 1f else 0.38f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            enabled = isDarkEnabled,
                            onClick = { actions.onAmoledChange(!isAmoledEnabled) },
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_eye_off),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.onboarding_full_black_oled),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        )
                    }
                    Switch(
                        checked = isAmoledEnabled && isDarkEnabled,
                        onCheckedChange = { if (isDarkEnabled) actions.onAmoledChange(it) },
                        enabled = isDarkEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageSlide(
    storageSummary: String?,
    permissions: OnboardingPermissions,
    actions: OnboardingActions,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_storage_permissions_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Storage destination card
        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.manga_save_location),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = storageSummary ?: stringResource(R.string.onboarding_default_destination),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = actions.onSelectDestination,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_storage),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.onboarding_select_destination))
                }
            }
        }
        // Permissions card
        Surface(
            shape = CARD_SHAPE,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                PermissionRow(
                    iconRes = R.drawable.ic_plug_large,
                    titleRes = R.string.onboarding_permission_install,
                    isGranted = permissions.hasInstall,
                    onClick = actions.onPermissionInstall,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                )
                PermissionRow(
                    iconRes = R.drawable.ic_notification,
                    titleRes = R.string.onboarding_permission_notifications,
                    isGranted = permissions.hasNotifications,
                    onClick = actions.onPermissionNotifications,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                )
                PermissionRow(
                    iconRes = R.drawable.ic_battery_outline,
                    titleRes = R.string.onboarding_permission_battery,
                    isGranted = permissions.hasBattery,
                    onClick = actions.onPermissionBattery,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    isGranted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (isGranted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isGranted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isGranted) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SyncSlide(isLoading: Boolean, actions: OnboardingActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_sync_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionCard(
            items = listOf(
                ActionItem(R.drawable.ic_google_g, R.string.sync_sign_in, !isLoading, actions.onSignInGoogle),
            ),
        )
        ActionCard(
            items = listOf(
                ActionItem(R.drawable.ic_backup_restore, R.string.onboarding_restore_dropsauce, !isLoading, actions.onRestoreDropSauce),
                ActionItem(R.drawable.ic_revert, R.string.onboarding_restore_tachiyomi, !isLoading, actions.onRestoreTachiyomi),
            ),
        )
    }
}

@Composable
private fun FinishSlide(actions: OnboardingActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_finish_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionCard(
            items = listOf(
                ActionItem(R.drawable.ic_github, R.string.source_code, true, actions.onOpenGithub),
                ActionItem(R.drawable.ic_discord, R.string.discord, true, actions.onOpenDiscord),
                ActionItem(R.drawable.ic_web, R.string.onboarding_visit_website, true, actions.onVisitWebsite),
            ),
        )
    }
}

private data class ActionItem(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ActionCard(items: List<ActionItem>) {
    Surface(
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                FilledTonalButton(
                    onClick = item.onClick,
                    enabled = item.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(item.label))
                }
            }
        }
    }
}
