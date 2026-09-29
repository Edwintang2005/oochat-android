package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.Constants
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.ui.agent.AgentViewModel
import ai.openonion.oochat.ui.agent.components.AdvancedSettingsSection
import ai.openonion.oochat.ui.agent.components.AgentFormState
import ai.openonion.oochat.ui.agent.components.EmbeddedDiscoveryPanel
import ai.openonion.oochat.ui.common.appViewModel
import ai.openonion.oochat.ui.common.rememberPermissionGate
import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.spacing
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Onboarding screen for first-time setup.
 *
 * Leads with [EmbeddedDiscoveryPanel], which auto-starts a radar scan and connects immediately to
 * a tapped agent. The manual form (Server URL / Agent Address / API Key) remains as a collapsed
 * fallback for Direct/peer-to-peer connections or relays with no discovery endpoint.
 * The actual connection attempt happens on the shared [ai.openonion.oochat.ui.loading.LoadingScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onProceedToConnect: () -> Unit,
    onNavigateToLogs: () -> Unit = {},
    onNavigateToDiscover: (serverUrl: String, agentAddress: String) -> Unit = { _, _ -> },
    pendingDiscoveredAddress: String? = null,
    onDiscoveredAddressConsumed: () -> Unit = {},
    // Owns form validation + ConnectionConfig persistence — the screen
    // itself stays declarative.
    onboardingViewModel: OnboardingViewModel = appViewModel(),
    // Drives both the discovery panel (discoverAgents/connectToDiscoveredAgent)
    // and the fallback form's saved-agents dropdown. Exposed as an overridable
    // parameter (rather than resolved unconditionally via appViewModel()
    // inside the body) so a Compose UI test can substitute a fake-injected
    // instance — this screen now triggers a real discovery call the moment
    // it composes, which a test must not let hit the network.
    agentViewModel: AgentViewModel = appViewModel()
) {
    val isPrefillLoaded by onboardingViewModel.isPrefillLoaded.collectAsState()
    val prefillConfig by onboardingViewModel.prefillConfig.collectAsState()

    // Computed once when prefill resolves, so the form's first composition already has any saved
    // config (see isPrefillLoaded).
    var formState by remember(isPrefillLoaded) {
        mutableStateOf(prefillConfig.toFormStateOrDefault())
    }
    val errorMessage by onboardingViewModel.errorMessage.collectAsState()

    val agentUiState by agentViewModel.uiState.collectAsState()

    // Address handed back from the Discover Agents screen (picker mode) —
    // apply it once, then tell the caller to clear it so it doesn't reapply
    // on a later recomposition (e.g. navigating away and back).
    LaunchedEffect(pendingDiscoveredAddress) {
        if (pendingDiscoveredAddress != null) {
            formState = formState.copy(agentAddress = pendingDiscoveredAddress)
            onDiscoveredAddressConsumed()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            // User-defined QR payload convention: serverUrl and agentAddress
            // joined by a "/" — split on the LAST "/" since serverUrl itself
            // contains "//" (e.g. "https://").
            val lastSlash = scanned.lastIndexOf('/')
            if (lastSlash > 0 && lastSlash < scanned.length - 1) {
                val scannedServerUrl = scanned.substring(0, lastSlash)
                val scannedAgentAddress = scanned.substring(lastSlash + 1)
                formState = formState.copy(
                    serverUrl = scannedServerUrl,
                    agentAddress = scannedAgentAddress,
                    useDirectConnection = !Constants.isDefaultServerUrl(scannedServerUrl)
                )
            }
        }
    }
    fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan a ConnectOnion agent QR code")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }
    // Shown once before the system camera prompt so the user knows the camera
    // only reads a QR code and records nothing.
    val requestCameraForScan = rememberPermissionGate(
        permission = Manifest.permission.CAMERA,
        rationaleTitle = "Camera access",
        rationaleMessage = "ConnectOnion uses the camera to scan an agent's QR code. Nothing is recorded or uploaded — the code is read on-device and discarded.",
        deniedMessage = "Camera access is off, so a QR code cannot be scanned. Allow it in Settings, or paste the agent's address into the field instead.",
        onGranted = ::launchScan
    )

    fun connect() {
        onboardingViewModel.connect(formState, onSaved = onProceedToConnect)
    }

    if (!isPrefillLoaded) {
        // Avoid showing a blank form that jumps to prefilled values.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        // Edge-to-edge stops the window resizing for the IME (API 30+ ignores
        // adjustResize once the decor no longer fits system windows), so the
        // form takes the keyboard inset itself. Scaffold excludes what this
        // consumes from its own contentWindowInsets, so no doubled bottom gap.
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                actions = {
                    IconButton(onClick = requestCameraForScan) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR code to connect"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg)
        ) {
            OnboardingIntro()

            EmbeddedDiscoveryPanel(
                formState = formState,
                onFormStateChange = { formState = it },
                savedAgents = agentUiState.agents,
                discoveredAgents = agentUiState.discoveredAgents,
                discoveryPhase = agentUiState.discoveryPhase,
                error = agentUiState.discoveryError,
                onDiscover = { url -> agentViewModel.discoverAgents(url) },
                onSelectDiscovered = { agent ->
                    agentViewModel.connectToDiscoveredAgent(agent, onSaved = onProceedToConnect)
                },
                actionLabel = "Connect",
                advancedSettings = {
                    AdvancedSettingsSection(
                        apiKey = formState.apiKey,
                        onApiKeyChange = { formState = formState.copy(apiKey = it) }
                    )
                },
                submitButton = {
                    // Error message — blended (low-alpha error-over-surface) tint
                    // with a slide+fade entrance, rather than a flat errorContainer
                    // card appearing instantly.
                    AnimatedVisibility(
                        visible = errorMessage != null,
                        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
                        exit = fadeOut(tween(150))
                    ) {
                        // Inline text, as in RecoveryScreen — a card here would
                        // match the weight of the Connect button right below it.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = MaterialTheme.spacing.lg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Button(
                        onClick = { connect() },
                        enabled = formState.isValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ButtonToken.FullWidth),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Connect")
                    }
                }
            )
        }
    }
}

/**
 * The screen's opening copy: what the app is, and what to expect of it.
 *
 * Secondary tier, not a 28sp banner — the focus is the form below and its
 * Connect button. Heading and supporting line are one unit so the Column's
 * 16dp does the separating rather than a Spacer between them.
 *
 * The accuracy disclaimer lives in that supporting line rather than under the
 * chat composer, where it was reread on every screenful and stopped being read
 * at all. Here it is part of the screen's one "here is the deal" sentence —
 * alongside the account and data facts, read once, before the first reply.
 */
@Composable
internal fun OnboardingIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
        Text(
            text = "Chat with AI agents on your own terms",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "ConnectOnion reaches agents running on the open web — " +
                "no account, and nothing of yours kept on our servers. " +
                "AI-generated responses may be inaccurate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Maps a possibly-null saved [ConnectionConfig] to the [AgentFormState] the
 * Setup form's manual fallback should start from — the saved values verbatim
 * when present, or [AgentFormState]'s ordinary blank defaults for a genuine
 * first run. [AgentFormState.useDirectConnection] is derived from whether the
 * saved `serverUrl` was one of the known default relays, since
 * [ConnectionConfig] itself doesn't store the mode the user picked it in.
 */
private fun ConnectionConfig?.toFormStateOrDefault(): AgentFormState {
    if (this == null) return AgentFormState()
    return AgentFormState(
        serverUrl = serverUrl,
        agentAddress = agentAddress.orEmpty(),
        apiKey = apiKey.orEmpty(),
        useDirectConnection = !Constants.isDefaultServerUrl(serverUrl)
    )
}
