package ai.openonion.oochat.di

import ai.openonion.oochat.data.local.SafePreferencesWrapper
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultAppContainerTest {

    private lateinit var context: Context
    private lateinit var container: DefaultAppContainer

    @Before
    fun buildContainer() {
        context = ApplicationProvider.getApplicationContext()
        container = DefaultAppContainer(context)
    }

    @Test
    fun `every declared dependency is constructible without throwing`() {
        assertNotNull(container.keyManager)
        assertNotNull(container.configRepository)
        assertNotNull(container.agentRepository)
        assertNotNull(container.sessionRepository)
        assertNotNull(container.messageRepository)
        assertNotNull(container.ignoredIdsStorage)
        assertNotNull(container.defaultAgentRepository)
        assertNotNull(container.discoveryRepository)
        assertNotNull(container.accountRepository)
        assertNotNull(container.imageAttachmentStore)
        assertNotNull(container.voiceRecorderStore)
        assertNotNull(container.voiceTranscriptionService)
        assertNotNull(container.appSettings)
        assertNotNull(container.persistenceTransaction)
    }

    @Test
    fun `by-lazy properties hand out the same instance on repeat access`() {
        assertSame(container.agentRepository, container.agentRepository)
        assertSame(container.keyManager, container.keyManager)
    }

    @Test
    fun `connectToAgentUseCase is one shared instance, not a per-caller factory`() {
        // The per-ViewModel factory this replaced gave LoadingScreen's probe
        // and ChatViewModel a socket each — two server sessions for one agent.
        assertSame(container.connectToAgentUseCase, container.connectToAgentUseCase)
    }

    @Test
    fun `agentDiscovery is one shared instance, so one OkHttp connection pool`() {
        // ChatViewModel used to build its own beside the use case's, giving
        // the two /info GETs of a single connect separate pools and threads.
        assertSame(container.agentDiscovery, container.agentDiscovery)
    }

    /**
     * [AppContainer.isSecureStorage] drives SettingsScreen's "not encrypted"
     * warning banner, so it has to report what the wrapper actually did. The
     * dangerous regression is an optimistic constant: it hides a live
     * plaintext fallback from the one screen that would have warned about it.
     * The fallback decision itself belongs to SafePreferencesWrapperTest.
     */
    @Test
    fun `isSecureStorage reports the plaintext fallback instead of claiming secure`() {
        val wrapperState = SafePreferencesWrapper(context).isSecure()
        assertFalse(
            "precondition: EncryptedSharedPreferences cannot initialise in this JVM, so the " +
                "wrapper is on its plaintext fallback — without that this assertion proves nothing",
            wrapperState
        )
        assertEquals(wrapperState, container.isSecureStorage)
    }
}
