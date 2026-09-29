package ai.openonion.oochat.ui.settings

import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.notification.AGENT_REPLY_CHANNEL_ID
import ai.openonion.oochat.ui.common.FontSizeOption
import ai.openonion.oochat.ui.common.appViewModel
import ai.openonion.oochat.ui.components.BackTopAppBar
import ai.openonion.oochat.ui.settings.components.AccountSection
import ai.openonion.oochat.ui.settings.components.BackupSeedSheet
import ai.openonion.oochat.ui.settings.components.CustomInstructionsSheet
import ai.openonion.oochat.ui.settings.components.DeleteAllConversationsSheet
import ai.openonion.oochat.ui.settings.components.DeleteAllDataSheet
import ai.openonion.oochat.ui.settings.components.FontSizeSheet
import ai.openonion.oochat.ui.settings.components.ImportKeySheet
import ai.openonion.oochat.ui.settings.components.InsecureStorageWarningBanner
import ai.openonion.oochat.ui.settings.components.ResetIdentitySheet
import ai.openonion.oochat.ui.settings.components.SectionDivider
import ai.openonion.oochat.ui.settings.components.SectionFootnote
import ai.openonion.oochat.ui.settings.components.SettingsRow
import ai.openonion.oochat.ui.settings.components.SettingsSection
import ai.openonion.oochat.ui.settings.components.SwitchRow
import ai.openonion.oochat.ui.settings.components.TermsAndPrivacySheet
import ai.openonion.oochat.ui.settings.components.ThemeModeRow
import ai.openonion.oochat.ui.theme.LocalThemeController
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/**
 * Settings screen. Every item here is wired to something real: AI Model,
 * Response style, Language, Analytics and Safe mode were removed because they
 * were inert controls with a "SOON" badge, and a shipped product shouldn't
 * advertise four switches that do nothing. "Clear connection data" clears only
 * the saved connection config, not all data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToAgents: () -> Unit,
    onNavigateToLogs: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = appViewModel()
) {
    val themeController = LocalThemeController.current
    val context = LocalContext.current
    val appVersionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            "—"
        }
    }
    val isSecureStorage = remember { context.appContainer.isSecureStorage }
    val isIdentityStorageSecure = remember { context.appContainer.isIdentityStorageSecure }
    var insecureStorageWarningDismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val uiState by viewModel.uiState.collectAsState()
    var showBackupSheet by remember { mutableStateOf(false) }
    var backupExport by remember { mutableStateOf<ai.openonion.oochat.crypto.KeyManager.BackupExport?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showResetSheet by remember { mutableStateOf(false) }

    // Real, persisted via AppSettings/DataStore — read through the
    // ViewModel's pass-through flows; writes go through its setters.
    val streamResponses by viewModel.streamingResponses.collectAsState(initial = true)
    val renderMarkdown by viewModel.renderMarkdown.collectAsState(initial = true)
    val hapticFeedback by viewModel.hapticFeedback.collectAsState(initial = true)
    val soundEffects by viewModel.soundEffects.collectAsState(initial = false)
    val memoryEnabled by viewModel.memoryEnabled.collectAsState(initial = true)
    val fontSizeIndex by viewModel.fontSizeIndex.collectAsState(initial = 1)
    val customInstructions by viewModel.customInstructions.collectAsState(initial = "")

    var showCustomInstructions by remember { mutableStateOf(false) }
    var showDeleteDataSheet by remember { mutableStateOf(false) }
    var showDeleteConversationsSheet by remember { mutableStateOf(false) }
    var showFontSizeSheet by remember { mutableStateOf(false) }
    var showTermsSheet by remember { mutableStateOf(false) }

    // Notification permission three-way coordination (pref vs. OS grant vs.
    // effective state) lives in SettingsViewModel; this screen only re-checks
    // on ON_RESUME so a grant/revoke made from system Settings while this
    // screen wasn't in the foreground is picked up as soon as the user
    // comes back, and forwards the OS dialog's result. The account balance
    // rides the same observer for the same reason the reference web
    // client's useIdentity hook refreshes on window `focus`: an agent could
    // have kept spending credits the whole time this screen wasn't visible.
    val notificationPermissionBlocked by viewModel.notificationPermissionBlocked.collectAsState()
    val pushNotificationsEffective by viewModel.pushNotificationsEffective.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshNotificationPermission()
                viewModel.loadAccount()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    if (showCustomInstructions) {
        CustomInstructionsSheet(
            initial = customInstructions,
            onSave = { viewModel.setCustomInstructions(it) },
            onDismiss = { showCustomInstructions = false }
        )
    }

    if (showFontSizeSheet) {
        FontSizeSheet(
            initialIndex = fontSizeIndex,
            onConfirm = { viewModel.setFontSizeIndex(it) },
            onDismiss = { showFontSizeSheet = false }
        )
    }

    if (showTermsSheet) {
        TermsAndPrivacySheet(onDismiss = { showTermsSheet = false })
    }

    backupExport?.let { export ->
        if (showBackupSheet) {
            BackupSeedSheet(
                export = export,
                onDismiss = { showBackupSheet = false }
            )
        }
    }

    if (showImportSheet) {
        ImportKeySheet(
            onImport = { input ->
                // getOrThrow() rethrows KeyManager's validation error synchronously
                // so ImportKeySheet's own try/catch shows it inline.
                viewModel.importIdentity(input).getOrThrow()
                showImportSheet = false
                scope.launch { snackbarHostState.showSnackbar("Identity imported") }
            },
            onDismiss = { showImportSheet = false }
        )
    }

    if (showResetSheet) {
        ResetIdentitySheet(
            onConfirm = {
                val fresh = viewModel.resetIdentity()
                showResetSheet = false
                // Surface the new phrase immediately for backup, matching
                // the web client's generateNewIdentity() flow.
                backupExport = ai.openonion.oochat.crypto.KeyManager.BackupExport.Phrase(fresh.mnemonic)
                showBackupSheet = true
            },
            onDismiss = { showResetSheet = false }
        )
    }

    if (showDeleteDataSheet) {
        DeleteAllDataSheet(
            onConfirm = {
                showDeleteDataSheet = false
                scope.launch {
                    viewModel.clearConnectionData()
                    snackbarHostState.showSnackbar("Connection data cleared")
                }
            },
            onDismiss = { showDeleteDataSheet = false }
        )
    }

    if (showDeleteConversationsSheet) {
        DeleteAllConversationsSheet(
            onConfirm = {
                showDeleteConversationsSheet = false
                scope.launch {
                    viewModel.deleteAllConversations()
                    snackbarHostState.showSnackbar("All conversations deleted")
                }
            },
            onDismiss = { showDeleteConversationsSheet = false }
        )
    }

    Scaffold(
        topBar = {
            BackTopAppBar(
                title = "Settings",
                onNavigateBack = onNavigateBack,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(bottom = MaterialTheme.spacing.xl)
        ) {
            // The identity variant stays put: it is not dismissible, so it does
            // not consult insecureStorageWarningDismissed either.
            if (!isSecureStorage && (!isIdentityStorageSecure || !insecureStorageWarningDismissed)) {
                InsecureStorageWarningBanner(
                    identityAffected = !isIdentityStorageSecure,
                    onDismiss = if (isIdentityStorageSecure) {
                        { insecureStorageWarningDismissed = true }
                    } else {
                        null
                    }
                )
            }

            AccountSection(
                walletAddress = uiState.walletAddress,
                apiKey = uiState.accountApiKey,
                onBackupSeed = {
                    backupExport = viewModel.exportBackup()
                    showBackupSheet = true
                },
                onImportKey = { showImportSheet = true },
                onReset = { showResetSheet = true }
            )

            SettingsSection("Appearance") {
                ThemeModeRow(current = themeController.mode, onChange = themeController.setMode)
            }

            // Was "Model" — the two model-picking rows in it were inert, and
            // what's left is about how a reply is displayed, not which model
            // produced it.
            SettingsSection("Responses") {
                SwitchRow(
                    label = "Show live progress",
                    subtitle = if (streamResponses) "Thinking and tool steps shown as they happen" else "Only the final reply is shown",
                    checked = streamResponses,
                    onChange = { viewModel.setStreamingResponses(it) }
                )
                SectionDivider()
                SwitchRow(
                    label = "Render Markdown",
                    subtitle = if (renderMarkdown) "Bold, code blocks, and lists formatted" else "Plain text only",
                    checked = renderMarkdown,
                    onChange = { viewModel.setRenderMarkdown(it) }
                )
            }

            SettingsSection("Interface") {
                SettingsRow(
                    label = "Font size",
                    value = FontSizeOption.fromIndex(fontSizeIndex).label,
                    onClick = { showFontSizeSheet = true }
                )
                SectionDivider()
                SwitchRow(
                    label = "Haptic feedback",
                    checked = hapticFeedback,
                    onChange = { viewModel.setHapticFeedback(it) }
                )
                SectionDivider()
                SwitchRow(
                    label = "Sound effects",
                    checked = soundEffects,
                    onChange = { viewModel.setSoundEffects(it) }
                )
            }

            SettingsSection("Personalization") {
                SwitchRow(
                    label = "Memory",
                    subtitle = "Remembers context across chats",
                    checked = memoryEnabled,
                    onChange = { viewModel.setMemoryEnabled(it) }
                )
                SectionDivider()
                SettingsRow(
                    label = "Custom instructions",
                    subtitle = "Set default behavior and tone",
                    onClick = { showCustomInstructions = true }
                )
            }

            SettingsSection("Notifications") {
                SwitchRow(
                    label = "Push notifications",
                    subtitle = if (notificationPermissionBlocked) {
                        "Permission denied — tap to open system settings"
                    } else {
                        "Background task completions"
                    },
                    subtitleColor = if (notificationPermissionBlocked) MaterialTheme.colorScheme.error else null,
                    checked = pushNotificationsEffective,
                    onChange = { enabled ->
                        when {
                            // The OS permission dialog won't reliably reappear
                            // after a denial, so route back to it via the
                            // system app-settings screen instead of
                            // re-launching a request that would silently no-op.
                            notificationPermissionBlocked -> {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                )
                            }
                            viewModel.shouldRequestNotificationPermission(enabled) -> {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else -> viewModel.setPushNotificationsEnabled(enabled)
                        }
                    }
                )
                if (pushNotificationsEffective) {
                    SectionDivider()
                    SettingsRow(
                        label = "Notification sound",
                        subtitle = "Manage in system settings",
                        onClick = {
                            // Sound is a per-channel OS property from API 26
                            // onward, and the system owns that picker UI — this
                            // app has no bundled sound assets of its own for it
                            // to offer here, so hand off to it directly.
                            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, AGENT_REPLY_CHANNEL_ID)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                FileLogger.w(LogTags.NOTIFICATIONS, "No channel settings screen to open: ${e.message}")
                            }
                        }
                    )
                }
            }

            SettingsSection("Data & Privacy") {
                SettingsRow(
                    icon = Icons.Default.Person,
                    label = "Agent List",
                    subtitle = "Manage your connected agents",
                    onClick = onNavigateToAgents
                )
                SectionDivider()
                SettingsRow(
                    icon = Icons.Default.Description,
                    label = "Logs",
                    subtitle = "View application logs",
                    onClick = onNavigateToLogs
                )
                SectionDivider()
                SettingsRow(
                    label = "Delete all conversations",
                    labelColor = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteConversationsSheet = true }
                )
                SectionDivider()
                SettingsRow(
                    label = "Clear connection data",
                    labelColor = MaterialTheme.colorScheme.error,
                    onClick = { showDeleteDataSheet = true }
                )
                // These three used to be 48dp list rows with an icon and a
                // divider each, which made static disclosure text look like
                // three tappable settings. One footnote says the same thing.
                SectionFootnote(
                    "Conversations and attached images are stored locally on this device. " +
                        "Voice recordings are never sent or saved."
                )
            }

            SettingsSection("About") {
                SettingsRow(
                    label = "Version",
                    value = appVersionName,
                    showChevron = false,
                    onClick = {}
                )
                SectionDivider()
                SettingsRow(
                    label = "Terms & Privacy",
                    onClick = { showTermsSheet = true }
                )
            }
        }
    }
}

