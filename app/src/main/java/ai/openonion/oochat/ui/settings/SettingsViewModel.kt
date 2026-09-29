package ai.openonion.oochat.ui.settings

import ai.openonion.oochat.crypto.IdentityManager
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.di.appContainer
import ai.openonion.oochat.ui.common.launchScoped
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Account/identity state for the Settings screen. Wallet address is a fast
 * local Keystore read; API key + profile come from OpenOnion's account
 * service and are null while loading or unauthenticated.
 * [accountLoadFailed] distinguishes "still loading" from "the fetch actually
 * failed" — both leave [accountProfile] null, and without the flag a genuine
 * failure looked identical to normal loading and never resolved into
 * anything the user could act on.
 */
data class SettingsUiState(
    val walletAddress: String = "",
    val accountApiKey: String? = null,
)

/**
 * ViewModel for [SettingsScreen]. The critical invariant: whenever the identity
 * changes (reset/import), [AccountRepository.invalidate] is called before any reload,
 * so the repository can never serve the previous identity's JWT/balance/profile.
 *
 * @JvmOverloads so the AndroidX default factory can still locate a (Application) constructor.
 */
open class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val identityManager: IdentityManager = application.appContainer.keyManager,
    private val accountRepository: AccountRepository = application.appContainer.accountRepository,
    private val appSettings: AppSettings = application.appContainer.appSettings,
    private val configRepository: ConnectionConfigRepository = application.appContainer.configRepository,
    private val sessionRepository: SessionRepository = application.appContainer.sessionRepository,
    // POST_NOTIFICATIONS is only a runtime permission from API 33 onward;
    // below that there's no OS grant to reconcile against. Injected (rather
    // than read from Build inline) so JVM tests can exercise both branches.
    private val requiresNotificationPermission: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    // Reads the *current* OS grant. Called at construction and again from
    // refreshNotificationPermission() on every ON_RESUME, so a grant/revoke
    // made from system Settings while the screen was backgrounded is picked
    // up as soon as the user comes back.
    private val notificationPermissionChecker: () -> Boolean = {
        !requiresNotificationPermission ||
            ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
    // Tests set this to false to control exactly when the first load runs.
    autoLoadAccountOnInit: Boolean = true,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // ── Notification permission three-way coordination ─────────────────
    // The persisted pref ([AppSettings.pushNotificationsEnabled]) is the
    // user's *intent*; the OS grant is a separate fact. These derived flows
    // reconcile the two — previously computed ad hoc in the Composable.

    private val _hasNotificationPermission = MutableStateFlow(notificationPermissionChecker())
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    /**
     * The user opted in but the OS permission is currently denied/revoked —
     * drives the "tap to open system settings" affordance instead of
     * re-requesting a dialog Android won't show again after a denial.
     */
    val notificationPermissionBlocked: StateFlow<Boolean> =
        combine(appSettings.pushNotificationsEnabled, _hasNotificationPermission) { pref, granted ->
            requiresNotificationPermission && pref && !granted
            // Eagerly (not WhileSubscribed) so tests reading .value without
            // collecting, and the first composition, both see true state.
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Pref AND (below API 33, or the grant actually exists) — what the toggle displays. */
    val pushNotificationsEffective: StateFlow<Boolean> =
        combine(appSettings.pushNotificationsEnabled, _hasNotificationPermission) { pref, granted ->
            pref && (!requiresNotificationPermission || granted)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Re-check the OS grant (screen calls this on every ON_RESUME). */
    fun refreshNotificationPermission() {
        _hasNotificationPermission.value = notificationPermissionChecker()
    }

    /**
     * True when flipping the toggle on must go through the OS permission
     * dialog first (API 33+, grant not yet given) instead of just
     * persisting the pref.
     */
    fun shouldRequestNotificationPermission(enabled: Boolean): Boolean =
        enabled && requiresNotificationPermission && !_hasNotificationPermission.value

    /** Outcome of the OS permission dialog: record the grant and align the pref with it. */
    fun onNotificationPermissionResult(granted: Boolean) {
        _hasNotificationPermission.value = granted
        viewModelScope.launch { appSettings.setPushNotificationsEnabled(granted) }
    }

    // ── Identity (Ed25519/BIP39) operations ────────────────────────────

    /**
     * Guards against an account load that was already in flight when the
     * identity switched from landing its (old-identity) result in uiState.
     * Only read/written on the main dispatcher — no synchronization needed
     * (AccountRepositoryImpl has its own epoch for the repository-level
     * version of the same race).
     */
    private var identityGeneration = 0

    /**
     * Overwrite the current identity with a brand-new mnemonic-backed one.
     * Returns the fresh keys/phrase so the screen can surface the phrase
     * for immediate backup (matching the web client's generateNewIdentity()).
     * Synchronous by design — the crypto is local and fast, and the caller
     * (ResetIdentitySheet's confirm) needs the mnemonic in hand.
     */
    fun resetIdentity(): KeyManager.MnemonicKeys {
        val fresh = identityManager.resetIdentity()
        onIdentityChanged(fresh.addressData.address)
        return fresh
    }

    /**
     * Import an identity from a 12/24-word phrase or raw hex key. Failure
     * (invalid phrase/key — thrown by KeyManager's validation) is returned
     * as a [Result] so ImportKeySheet can show it inline; nothing is
     * invalidated or reloaded unless the import actually succeeded.
     */
    fun importIdentity(input: String): Result<KeyManager.AddressData> =
        runCatching { identityManager.importFromPhraseOrHex(input) }
            .onSuccess { keys -> onIdentityChanged(keys.address) }

    /** What "Back up seed phrase" shows — see [KeyManager.BackupExport]. */
    fun exportBackup(): KeyManager.BackupExport = identityManager.exportBackup()

    /**
     * The identity-switch invariant, in one place and in the only safe
     * order: the new keys are already persisted (KeyManager.save() ran
     * inside reset/import before we get here), so
     * 1. invalidate the account repository FIRST — from this point the
     *    old JWT can never be served again, and any load still in flight
     *    is fenced off by the repository's identity epoch;
     * 2. bump [identityGeneration] so a completed-but-unapplied old load
     *    can't write stale account fields into uiState after this;
     * 3. clear the displayed account state (old balance/API key must not
     *    linger while the new identity authenticates);
     * 4. reload as the new identity.
     */
    private fun onIdentityChanged(newAddress: String) {
        accountRepository.invalidate()
        identityGeneration++
        _uiState.update {
            it.copy(
                walletAddress = newAddress,
                accountApiKey = null
            )
        }
        FileLogger.i(LogTags.ACCOUNT, "identity changed → account cache invalidated, reloading")
        loadAccount()
    }

    // ── Account loading ────────────────────────────────────────────────

    fun loadAccount() {
        val generation = identityGeneration
        viewModelScope.launch {
            accountRepository.loadAccount()
                .onSuccess { snapshot ->
                    if (generation != identityGeneration) return@launch
                    _uiState.update {
                        it.copy(
                            accountApiKey = snapshot.apiKey
                        )
                    }
                }
                .onFailure {
                    if (generation != identityGeneration) return@launch
                    FileLogger.w(LogTags.ACCOUNT, "loadAccount failed; API key stays unset")
                }
        }
    }

    // ── Destructive data actions ───────────────────────────────────────

    /** Suspend (not fire-and-forget) so the screen can sequence its snackbar after completion. */
    suspend fun deleteAllConversations() {
        val sessions = sessionRepository.getAllSessions().first()
        sessions.forEach { sessionRepository.deleteSession(it.id) }
    }

    /** See [deleteAllConversations] for why this suspends. */
    suspend fun clearConnectionData() {
        configRepository.deleteConfig()
    }

    // ── AppSettings pass-through ───────────────────────────────────────
    // Reads are the raw persisted flows (screen collects with its own
    // initial values, same as before); writes go through the ViewModel so
    // the Composable never touches DataStore directly.

    val streamingResponses: Flow<Boolean> = appSettings.streamingResponses
    val renderMarkdown: Flow<Boolean> = appSettings.renderMarkdown
    val hapticFeedback: Flow<Boolean> = appSettings.hapticFeedback
    val soundEffects: Flow<Boolean> = appSettings.soundEffects
    val memoryEnabled: Flow<Boolean> = appSettings.memoryEnabled
    val fontSizeIndex: Flow<Int> = appSettings.fontSizeIndex
    val customInstructions: Flow<String> = appSettings.customInstructions
    val notificationSound: Flow<String> = appSettings.notificationSound
    val analyticsEnabled: Flow<Boolean> = appSettings.analyticsEnabled
    val safeModeEnabled: Flow<Boolean> = appSettings.safeModeEnabled

    fun setStreamingResponses(value: Boolean) = persist { appSettings.setStreamingResponses(value) }
    fun setRenderMarkdown(value: Boolean) = persist { appSettings.setRenderMarkdown(value) }
    fun setHapticFeedback(value: Boolean) = persist { appSettings.setHapticFeedback(value) }
    fun setSoundEffects(value: Boolean) = persist { appSettings.setSoundEffects(value) }
    fun setMemoryEnabled(value: Boolean) = persist { appSettings.setMemoryEnabled(value) }
    fun setFontSizeIndex(value: Int) = persist { appSettings.setFontSizeIndex(value) }
    fun setCustomInstructions(value: String) = persist { appSettings.setCustomInstructions(value) }
    fun setPushNotificationsEnabled(value: Boolean) = persist { appSettings.setPushNotificationsEnabled(value) }
    fun setNotificationSound(value: String) = persist { appSettings.setNotificationSound(value) }
    fun setAnalyticsEnabled(value: Boolean) = persist { appSettings.setAnalyticsEnabled(value) }
    fun setSafeModeEnabled(value: Boolean) = persist { appSettings.setSafeModeEnabled(value) }

    // Thin alias over the shared [launchScoped] fire-and-forget helper,
    // kept for the many `set…` delegators above that read as `persist { … }`.
    private fun persist(write: suspend () -> Unit) = launchScoped { write() }

    init {
        // Fast local Keystore read — same pattern ChatViewModel's
        // initialMyAddress default already uses.
        _uiState.update { it.copy(walletAddress = identityManager.loadOrGenerate().address) }
        if (autoLoadAccountOnInit) {
            loadAccount()
        }
    }
}
