package ai.openonion.oochat.ui.settings

import ai.openonion.oochat.crypto.IdentityManager
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.AppSettings
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.data.repository.AccountRepository
import ai.openonion.oochat.data.repository.AccountSnapshot
import ai.openonion.oochat.data.repository.ConnectionConfigRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.UserProfile
import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Same conventions as [ai.openonion.oochat.ui.chat.ChatViewModelTest]:
 * the `Application()` stub is safe because every dependency is an in-memory
 * fake, so no Android API is ever hit. Focus areas, per the extraction's
 * contract:
 *  - identity reset/import invalidates the shared account-repository cache
 *    (the security fix: a switched identity must never see the old
 *    identity's JWT/balance/profile) and does so in the right order
 *  - a failed import invalidates nothing
 *  - an account load already in flight across an identity switch can't land
 *    stale data in uiState
 *  - notification-permission three-way state transitions
 *  - account-load-failure state
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    // ── Test fakes ─────────────────────────────────────────────────────

    private class FakeIdentityManager : IdentityManager {
        var currentAddress = "0xOLD-IDENTITY"
        var resetCount = 0
        var lastImportedInput: String? = null
        var importError: Exception? = null

        private fun addressData(address: String) = KeyManager.AddressData(
            address = address,
            shortAddress = "${address.take(6)}...${address.takeLast(4)}",
            publicKey = ByteArray(32),
            privateKey = ByteArray(64)
        )

        override fun loadOrGenerate(): KeyManager.AddressData = addressData(currentAddress)

        override fun resetIdentity(): KeyManager.MnemonicKeys {
            resetCount++
            currentAddress = "0xRESET-IDENTITY"
            return KeyManager.MnemonicKeys(addressData(currentAddress), "abandon ".repeat(11) + "about")
        }

        override fun importFromPhraseOrHex(input: String): KeyManager.AddressData {
            importError?.let { throw it }
            lastImportedInput = input
            currentAddress = "0xIMPORTED-IDENTITY"
            return addressData(currentAddress)
        }

        override fun exportBackup(): KeyManager.BackupExport =
            KeyManager.BackupExport.Phrase("exported phrase words")
    }

    private class FakeAccountRepository : AccountRepository {
        /** Interleaved call log — proves invalidate() happens before the post-switch reload. */
        val calls = mutableListOf<String>()
        var invalidateCount = 0
        var defaultResult: Result<AccountSnapshot> = Result.success(snapshot("token-default"))
        /** Consumed first-in-first-out before falling back to [defaultResult]. */
        val queuedResults = ArrayDeque<Result<AccountSnapshot>>()
        /** When set, loadAccount suspends here — lets a test interleave an identity switch mid-load. */
        var blockUntil: CompletableDeferred<Unit>? = null

        override suspend fun loadAccount(forceReauth: Boolean): Result<AccountSnapshot> {
            calls += "load"
            val result = queuedResults.removeFirstOrNull() ?: defaultResult
            blockUntil?.await()
            return result
        }

        override fun invalidate() {
            calls += "invalidate"
            invalidateCount++
        }

        companion object {
            fun snapshot(token: String, balance: Double = 5.0) = AccountSnapshot(
                apiKey = token,
                profile = UserProfile(
                    publicKey = "0xabc",
                    creditsUsd = 10.0,
                    totalCostUsd = 5.0,
                    balanceUsd = balance
                )
            )
        }
    }

    private class FakeAppSettings : AppSettings {
        private val renderMarkdownFlow = MutableStateFlow(true)
        private val fontSizeIndexFlow = MutableStateFlow(1)
        private val hapticFeedbackFlow = MutableStateFlow(true)
        private val soundEffectsFlow = MutableStateFlow(false)
        private val streamingResponsesFlow = MutableStateFlow(true)
        private val memoryEnabledFlow = MutableStateFlow(true)
        private val customInstructionsFlow = MutableStateFlow("")
        val pushNotificationsEnabledFlow = MutableStateFlow(false)
        private val notificationSoundFlow = MutableStateFlow("Chime")
        private val analyticsEnabledFlow = MutableStateFlow(false)
        private val safeModeEnabledFlow = MutableStateFlow(true)

        override val renderMarkdown: Flow<Boolean> = renderMarkdownFlow
        override suspend fun setRenderMarkdown(value: Boolean) { renderMarkdownFlow.value = value }
        override val fontSizeIndex: Flow<Int> = fontSizeIndexFlow
        override suspend fun setFontSizeIndex(value: Int) { fontSizeIndexFlow.value = value }
        override val hapticFeedback: Flow<Boolean> = hapticFeedbackFlow
        override suspend fun setHapticFeedback(value: Boolean) { hapticFeedbackFlow.value = value }
        override val soundEffects: Flow<Boolean> = soundEffectsFlow
        override suspend fun setSoundEffects(value: Boolean) { soundEffectsFlow.value = value }
        override val streamingResponses: Flow<Boolean> = streamingResponsesFlow
        override suspend fun setStreamingResponses(value: Boolean) { streamingResponsesFlow.value = value }
        override val memoryEnabled: Flow<Boolean> = memoryEnabledFlow
        override suspend fun setMemoryEnabled(value: Boolean) { memoryEnabledFlow.value = value }
        override val customInstructions: Flow<String> = customInstructionsFlow
        override suspend fun setCustomInstructions(value: String) { customInstructionsFlow.value = value }
        override val pushNotificationsEnabled: Flow<Boolean> = pushNotificationsEnabledFlow
        override suspend fun setPushNotificationsEnabled(value: Boolean) { pushNotificationsEnabledFlow.value = value }
        override val notificationSound: Flow<String> = notificationSoundFlow
        override suspend fun setNotificationSound(value: String) { notificationSoundFlow.value = value }
        override val analyticsEnabled: Flow<Boolean> = analyticsEnabledFlow
        override suspend fun setAnalyticsEnabled(value: Boolean) { analyticsEnabledFlow.value = value }
        override val safeModeEnabled: Flow<Boolean> = safeModeEnabledFlow
        override suspend fun setSafeModeEnabled(value: Boolean) { safeModeEnabledFlow.value = value }
    }

    private class FakeConfigRepository : ConnectionConfigRepository {
        var configValue: ConnectionConfig? = null
        override suspend fun getConfig() = configValue
        override fun observeConfig() = MutableStateFlow(configValue)
        override suspend fun saveConfig(config: ConnectionConfig) { configValue = config }
        override suspend fun deleteConfig() { configValue = null }
        override suspend fun hasConfig() = configValue != null
        override suspend fun updateLastConnected(timestamp: Long) {}
        override suspend fun updateAgentAddress(address: String?) {}
    }

    private class FakeSessionRepository : SessionRepository {
        private val all = MutableStateFlow<List<ChatSession>>(emptyList())
        private var counter = 0

        override fun getAllSessions(): Flow<List<ChatSession>> = all
        override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> =
            all.map { list -> list.filter { it.agentId == agentId } }
        override suspend fun getSessionById(id: String) = all.value.find { it.id == id }
        override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
            val session = ChatSession(id = id, agentId = agentId, title = title, createdAt = 0L, updatedAt = 0L)
            all.value = all.value + session
            return session
        }
        override suspend fun deleteSession(sessionId: String) {
            all.value = all.value.filterNot { it.id == sessionId }
        }
        override suspend fun renameSession(sessionId: String, newTitle: String) {}
        override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {}

        val sessionCount: Int get() = all.value.size
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var identityManager: FakeIdentityManager
    private lateinit var accountRepo: FakeAccountRepository
    private lateinit var appSettings: FakeAppSettings
    private lateinit var configRepo: FakeConfigRepository
    private lateinit var sessionRepo: FakeSessionRepository
    private var permissionGranted = false

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        identityManager = FakeIdentityManager()
        accountRepo = FakeAccountRepository()
        appSettings = FakeAppSettings()
        configRepo = FakeConfigRepository()
        sessionRepo = FakeSessionRepository()
        permissionGranted = false
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    /** Constructed per-test (not in setUp) so tests control init-time behavior. */
    private fun createVm(
        requiresNotificationPermission: Boolean = true,
        autoLoadAccountOnInit: Boolean = true,
    ) = SettingsViewModel(
        application = Application(),
        identityManager = identityManager,
        accountRepository = accountRepo,
        appSettings = appSettings,
        configRepository = configRepo,
        sessionRepository = sessionRepo,
        requiresNotificationPermission = requiresNotificationPermission,
        notificationPermissionChecker = { !requiresNotificationPermission || permissionGranted },
        autoLoadAccountOnInit = autoLoadAccountOnInit,
    )

    // ── Construction / initial account load ────────────────────────────

    @Test
    fun `init seeds walletAddress from the identity manager and loads the account`() = runTest(testDispatcher) {
        val vm = createVm()
        testScheduler.runCurrent()

        assertEquals("0xOLD-IDENTITY", vm.uiState.value.walletAddress)
        assertEquals("token-default", vm.uiState.value.accountApiKey)
    }

    @Test
    fun `account load failure leaves the API key unset and a later successful reload fills it`() = runTest(testDispatcher) {
        accountRepo.queuedResults += Result.failure(RuntimeException("network down"))
        val vm = createVm()
        testScheduler.runCurrent()

        assertNull("a failed load must leave the key unset", vm.uiState.value.accountApiKey)

        vm.loadAccount() // defaultResult (success) now applies
        testScheduler.runCurrent()

        assertEquals("a successful reload must fill the key", "token-default", vm.uiState.value.accountApiKey)
        assertEquals("token-default", vm.uiState.value.accountApiKey)
    }

    // ── Identity switch invalidates the shared account cache ───────────

    @Test
    fun `resetIdentity invalidates the account cache before reloading as the new identity`() = runTest(testDispatcher) {
        accountRepo.queuedResults += Result.success(FakeAccountRepository.snapshot("old-token"))
        accountRepo.queuedResults += Result.success(FakeAccountRepository.snapshot("new-token"))
        val vm = createVm()
        testScheduler.runCurrent()
        assertEquals("old-token", vm.uiState.value.accountApiKey)

        val fresh = vm.resetIdentity()
        testScheduler.runCurrent()

        assertEquals(1, identityManager.resetCount)
        assertEquals(1, accountRepo.invalidateCount)
        assertEquals(
            "invalidate must land between the initial load and the post-reset reload " +
                "so the reload can never be served the old identity's cached JWT",
            listOf("load", "invalidate", "load"),
            accountRepo.calls
        )
        assertEquals("0xRESET-IDENTITY", vm.uiState.value.walletAddress)
        assertEquals("the reload must surface the NEW identity's snapshot", "new-token", vm.uiState.value.accountApiKey)
        assertTrue("the fresh mnemonic must be returned for immediate backup", fresh.mnemonic.isNotBlank())
    }

    @Test
    fun `importIdentity invalidates the account cache and reloads as the imported identity`() = runTest(testDispatcher) {
        accountRepo.queuedResults += Result.success(FakeAccountRepository.snapshot("old-token"))
        accountRepo.queuedResults += Result.success(FakeAccountRepository.snapshot("imported-token"))
        val vm = createVm()
        testScheduler.runCurrent()

        val result = vm.importIdentity("word ".repeat(11) + "word")
        testScheduler.runCurrent()

        assertTrue(result.isSuccess)
        assertEquals(1, accountRepo.invalidateCount)
        assertEquals(listOf("load", "invalidate", "load"), accountRepo.calls)
        assertEquals("0xIMPORTED-IDENTITY", vm.uiState.value.walletAddress)
        assertEquals("imported-token", vm.uiState.value.accountApiKey)
    }

    @Test
    fun `a failed import invalidates nothing and leaves the current identity untouched`() = runTest(testDispatcher) {
        val vm = createVm()
        testScheduler.runCurrent()
        identityManager.importError = IllegalArgumentException("Invalid key length — expected 128 hex characters")

        val result = vm.importIdentity("not a real key")
        testScheduler.runCurrent()

        assertTrue("the validation error must surface to the sheet via Result", result.isFailure)
        assertEquals(0, accountRepo.invalidateCount)
        assertEquals("0xOLD-IDENTITY", vm.uiState.value.walletAddress)
        assertEquals("token-default", vm.uiState.value.accountApiKey)
    }

    @Test
    fun `identity switch immediately clears the old identity's displayed account state`() = runTest(testDispatcher) {
        // Make the post-switch reload never complete — the displayed state
        // must ALREADY be cleared, not merely eventually replaced.
        val vm = createVm()
        testScheduler.runCurrent()
        assertEquals("token-default", vm.uiState.value.accountApiKey)
        accountRepo.blockUntil = CompletableDeferred()

        vm.resetIdentity()
        testScheduler.runCurrent()

        assertNull("old API key must not linger while the new identity authenticates", vm.uiState.value.accountApiKey)
    }

    @Test
    fun `an account load in flight across an identity switch cannot land stale data`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        accountRepo.blockUntil = gate
        accountRepo.queuedResults += Result.success(FakeAccountRepository.snapshot("old-token"))
        accountRepo.queuedResults += Result.success(FakeAccountRepository.snapshot("new-token"))
        val vm = createVm() // init load suspends on the gate holding old-token
        testScheduler.runCurrent()

        vm.importIdentity("0x" + "ab".repeat(64)) // switch while the old load is still in flight
        testScheduler.runCurrent()

        gate.complete(Unit) // both loads resume: stale first, then the new identity's
        testScheduler.runCurrent()

        assertEquals(
            "the pre-switch load's old-identity snapshot must be dropped, not applied",
            "new-token",
            vm.uiState.value.accountApiKey
        )
    }

    @Test
    fun `exportBackup passes through the identity manager's export`() {
        val vm = createVm(autoLoadAccountOnInit = false)

        val export = vm.exportBackup()

        assertTrue(export is KeyManager.BackupExport.Phrase)
        assertEquals("exported phrase words", (export as KeyManager.BackupExport.Phrase).mnemonic)
    }

    // ── Notification-permission three-way coordination ─────────────────

    @Test
    fun `pref on but OS grant missing means blocked and not effective`() = runTest(testDispatcher) {
        permissionGranted = false
        val vm = createVm(requiresNotificationPermission = true)
        appSettings.pushNotificationsEnabledFlow.value = true
        testScheduler.runCurrent()

        assertTrue(vm.notificationPermissionBlocked.value)
        assertFalse(vm.pushNotificationsEffective.value)
    }

    @Test
    fun `granting the permission in system settings is picked up by refreshNotificationPermission`() = runTest(testDispatcher) {
        permissionGranted = false
        val vm = createVm(requiresNotificationPermission = true)
        appSettings.pushNotificationsEnabledFlow.value = true
        testScheduler.runCurrent()
        assertTrue(vm.notificationPermissionBlocked.value)

        permissionGranted = true
        vm.refreshNotificationPermission() // what the screen calls on ON_RESUME
        testScheduler.runCurrent()

        assertFalse(vm.notificationPermissionBlocked.value)
        assertTrue(vm.pushNotificationsEffective.value)
    }

    @Test
    fun `below API 33 the pref alone is effective and never blocked`() = runTest(testDispatcher) {
        val vm = createVm(requiresNotificationPermission = false)
        appSettings.pushNotificationsEnabledFlow.value = true
        testScheduler.runCurrent()

        assertTrue(vm.pushNotificationsEffective.value)
        assertFalse(vm.notificationPermissionBlocked.value)
        assertFalse(
            "no runtime permission exists to request below API 33",
            vm.shouldRequestNotificationPermission(enabled = true)
        )
    }

    @Test
    fun `shouldRequestNotificationPermission only when enabling without the grant`() = runTest(testDispatcher) {
        permissionGranted = false
        val vm = createVm(requiresNotificationPermission = true)
        testScheduler.runCurrent()

        assertTrue(vm.shouldRequestNotificationPermission(enabled = true))
        assertFalse("turning the toggle OFF never needs the OS dialog", vm.shouldRequestNotificationPermission(enabled = false))

        permissionGranted = true
        vm.refreshNotificationPermission()
        assertFalse("with the grant in hand the pref can be set directly", vm.shouldRequestNotificationPermission(enabled = true))
    }

    @Test
    fun `permission dialog result aligns both the grant state and the persisted pref`() = runTest(testDispatcher) {
        permissionGranted = false
        val vm = createVm(requiresNotificationPermission = true)
        testScheduler.runCurrent()

        vm.onNotificationPermissionResult(true)
        testScheduler.runCurrent()

        assertTrue(vm.hasNotificationPermission.value)
        assertTrue(appSettings.pushNotificationsEnabledFlow.value)
        assertTrue(vm.pushNotificationsEffective.value)

        vm.onNotificationPermissionResult(false)
        testScheduler.runCurrent()

        assertFalse(vm.hasNotificationPermission.value)
        assertFalse("a denial must also clear the persisted intent", appSettings.pushNotificationsEnabledFlow.value)
        assertFalse(vm.pushNotificationsEffective.value)
    }

    // ── Destructive data actions ───────────────────────────────────────

    @Test
    fun `deleteAllConversations removes every session`() = runTest(testDispatcher) {
        sessionRepo.createSession("agent-1", "one")
        sessionRepo.createSession("agent-1", "two")
        val vm = createVm(autoLoadAccountOnInit = false)

        vm.deleteAllConversations()

        assertEquals(0, sessionRepo.sessionCount)
    }

    @Test
    fun `clearConnectionData deletes the saved config`() = runTest(testDispatcher) {
        configRepo.configValue = ConnectionConfig(serverUrl = "https://x", agentAddress = "0xA")
        val vm = createVm(autoLoadAccountOnInit = false)

        vm.clearConnectionData()

        assertNull(configRepo.configValue)
    }

    // ── AppSettings write pass-through ─────────────────────────────────

    @Test
    fun `setters persist through to AppSettings`() = runTest(testDispatcher) {
        val vm = createVm(autoLoadAccountOnInit = false)

        vm.setStreamingResponses(false)
        vm.setFontSizeIndex(3)
        vm.setCustomInstructions("be brief")
        vm.setNotificationSound("Bell")
        testScheduler.runCurrent()

        assertEquals(false, appSettings.streamingResponses.firstValue())
        assertEquals(3, appSettings.fontSizeIndex.firstValue())
        assertEquals("be brief", appSettings.customInstructions.firstValue())
        assertEquals("Bell", appSettings.notificationSound.firstValue())
    }

    private fun <T> Flow<T>.firstValue(): T = (this as MutableStateFlow<T>).value
}
