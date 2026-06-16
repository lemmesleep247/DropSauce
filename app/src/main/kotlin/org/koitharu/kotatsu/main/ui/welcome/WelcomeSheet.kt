package org.koitharu.kotatsu.main.ui.welcome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.ui.restore.RestoreDialogFragment
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.SheetWelcomeComposeBinding
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class WelcomeSheet : BaseAdaptiveSheet<SheetWelcomeComposeBinding>() {

    private val viewModel by viewModels<WelcomeViewModel>()

    private var permissionStates by mutableStateOf(OnboardingPermissions(false, false, false))

    private val restoreTachiyomiLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.restoreBackup(uri)
    }

    private val restoreDropSauceLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) RestoreDialogFragment.show(parentFragmentManager, uri)
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.handleGoogleSignInResult(result.data) }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionStates() }

    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissionStates() }

    private val batteryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionStates() }

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetWelcomeComposeBinding = SheetWelcomeComposeBinding.inflate(inflater, container, false)

    override fun onViewBindingCreated(binding: SheetWelcomeComposeBinding, savedInstanceState: Bundle?) {
        super.onViewBindingCreated(binding, savedInstanceState)
        disableFitToContents()

        viewModel.onGoogleSignInLaunch.observeEvent(viewLifecycleOwner) { intent ->
            googleSignInLauncher.launch(intent)
        }
        viewModel.onGoogleSignInCompleted.observeEvent(viewLifecycleOwner) { success ->
            Toast.makeText(
                requireContext(),
                if (success) R.string.sync_completed else R.string.sync_error,
                Toast.LENGTH_LONG,
            ).show()
        }
        viewModel.onBackupRestored.observeEvent(viewLifecycleOwner) { result ->
            val text = if (result.error != null) {
                result.error.getDisplayMessage(resources)
            } else {
                getString(R.string.data_restored_success)
            }
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }

        refreshPermissionStates()

        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeView.setContent {
            DropSauceTheme {
                val theme by viewModel.selectedTheme.collectAsState()
                val colorScheme by viewModel.selectedColorScheme.collectAsState()
                val amoled by viewModel.isAmoledEnabled.collectAsState()
                val storage by viewModel.storageSummary.collectAsState()
                val loading by viewModel.isLoading.collectAsState()
                OnboardingScreen(
                    selectedTheme = theme,
                    selectedColorScheme = colorScheme,
                    isAmoledEnabled = amoled,
                    storageSummary = storage,
                    isLoading = loading,
                    permissions = permissionStates,
                    actions = OnboardingActions(
                        onThemeChange = { mode ->
                            viewModel.setTheme(mode)
                            AppCompatDelegate.setDefaultNightMode(mode)
                        },
                        onColorSchemeChange = { name ->
                            val scheme = ColorScheme.entries.find { it.name == name }
                            if (scheme != null && viewModel.selectedColorScheme.value != scheme) {
                                viewModel.setColorScheme(scheme)
                                requireActivity().recreate()
                            }
                        },
                        onAmoledChange = { enabled ->
                            viewModel.setAmoledTheme(enabled)
                            requireActivity().recreate()
                        },
                        onAmoledReset = { viewModel.setAmoledTheme(false) },
                        onSelectDestination = { router.showDirectorySelectDialog() },
                        onPermissionInstall = ::requestInstallPermission,
                        onPermissionNotifications = ::requestNotificationsPermission,
                        onPermissionBattery = ::requestBatteryOptimizationPermission,
                        onSignInGoogle = { viewModel.launchGoogleSignIn() },
                        onRestoreDropSauce = {
                            restoreDropSauceLauncher.launch(arrayOf("application/*", "*/*"))
                        },
                        onRestoreTachiyomi = {
                            restoreTachiyomiLauncher.launch(arrayOf("application/*", "*/*"))
                        },
                        onOpenGithub = {
                            openExternalLink(getString(R.string.url_github), getString(R.string.source_code))
                        },
                        onOpenDiscord = {
                            openExternalLink(getString(R.string.url_discord_web), getString(R.string.discord))
                        },
                        onVisitWebsite = {
                            openExternalLink(
                                getString(R.string.url_dropsauce_website),
                                getString(R.string.onboarding_visit_website),
                            )
                        },
                        onFinish = {
                            viewModel.completeOnboarding()
                            dismiss()
                        },
                    ),
                )
            }
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageSummary()
        refreshPermissionStates()
        clearAmoledIfLightMode()
    }

    private fun refreshPermissionStates() {
        permissionStates = OnboardingPermissions(
            hasInstall = hasInstallPermission(),
            hasNotifications = hasNotificationPermission(),
            hasBattery = isIgnoringBatteryOptimizations(),
        )
    }

    private fun clearAmoledIfLightMode() {
        if (!viewModel.isAmoledEnabled.value) return
        val isCurrentlyDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (viewModel.selectedTheme.value) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> isCurrentlyDark
        }
        if (!isDark) viewModel.setAmoledTheme(false)
    }

    private fun hasInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return requireContext().packageManager.canRequestPackageInstalls()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = requireContext().getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            installPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${requireContext().packageName}"),
                ),
            )
        }.onFailure {
            Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) return
        notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestBatteryOptimizationPermission() {
        if (isIgnoringBatteryOptimizations()) return
        runCatching {
            batteryPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${requireContext().packageName}"),
                ),
            )
        }.onFailure {
            Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun openExternalLink(url: String, title: String) {
        if (!router.openExternalBrowser(url, title)) {
            Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
        }
    }
}
