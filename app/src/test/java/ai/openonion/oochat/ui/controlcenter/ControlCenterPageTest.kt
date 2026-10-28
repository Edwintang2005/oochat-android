package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ControlCenterApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControlCenterPageTest {

    private val revision = "sha256:" + "e".repeat(64)
    private val app = ControlCenterApp(url = "https://r-1.Apps.Example.net:443/index.html", revision = revision)
    private val prefix = ControlCenterHost.PREFIX

    private val toPage = mutableListOf<JsonObject>()
    private var clockMs = 0L
    private var epochs = 0
    private var blocks = 0
    private val sent = mutableListOf<String>()

    private val actions = object : ControlCenterActions {
        val result = CompletableDeferred<String>()
        override suspend fun sendMessage(message: String, conversation: ControlCenterConversation): String {
            sent += message
            return result.await()
        }

        override suspend fun runSkill(skill: String, args: String?, conversation: ControlCenterConversation) =
            error("not used")
    }

    private val snapshot = ControlCenterSnapshot(
        sessionId = "conv-1",
        agentAddress = "0xagent",
        status = ControlCenterAgentStatus.IDLE,
        connectionState = ControlCenterConnection.CONNECTED,
        skills = emptyList()
    )

    private fun kotlinx.coroutines.test.TestScope.page() = ControlCenterPage(
        app = app,
        snapshot = { snapshot },
        actions = actions,
        scope = backgroundScope,
        toPage = { toPage += Json.parseToJsonElement(it).jsonObject },
        onBlocked = { blocks++ },
        nowMs = { clockMs },
        newEpoch = { "epoch-${++epochs}" }
    )

    private fun kinds() = toPage.map { it["kind"]!!.jsonPrimitive.content }

    /** A message the app posts on its port, as host.js relays it. */
    private fun fromApp(epoch: String, fields: JsonObject.() -> JsonObject = { this }) = buildJsonObject {
        put("kind", "message")
        put(
            "data",
            buildJsonObject {
                put("version", 1)
                put("revision", revision)
                put("epoch", epoch)
                put("type", prefix + "request")
                put("id", "request-1")
                put("action", "send_message")
                put("payload", buildJsonObject { put("message", "hi") })
            }.fields().toString()
        )
    }.toString()

    @Test
    fun `the page is told what to frame once it is ready`() = runTest {
        val page = page()

        page.onPageMessage("""{"kind":"page-ready"}""")

        val load = toPage.single()
        assertEquals("load", load["kind"]!!.jsonPrimitive.content)
        assertEquals(app.url, load["url"]!!.jsonPrimitive.content)
        assertEquals("https://r-1.apps.example.net", load["origin"]!!.jsonPrimitive.content)
        assertEquals(revision, load["revision"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a ready app is connected with a fresh epoch and sent its first snapshot`() = runTest {
        val page = page()
        page.onPageMessage("""{"kind":"page-ready"}""")

        page.onPageMessage("""{"kind":"app-ready"}""")

        assertEquals(listOf("load", "connect", "message"), kinds())
        assertEquals("epoch-1", toPage[1]["epoch"]!!.jsonPrimitive.content)
        val first = Json.parseToJsonElement(toPage[2]["data"]!!.jsonPrimitive.content).jsonObject
        assertEquals(prefix + "snapshot", first["type"]!!.jsonPrimitive.content)
        assertEquals("epoch-1", first["epoch"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the app's requests reach the chat and are answered through the page`() = runTest {
        val page = page()
        page.onPageMessage("""{"kind":"app-ready"}""")

        page.onPageMessage(fromApp("epoch-1"))
        runCurrent()
        actions.result.complete("conv-1")
        runCurrent()

        assertEquals(listOf("hi"), sent)
        val reply = Json.parseToJsonElement(toPage.last()["data"]!!.jsonPrimitive.content).jsonObject
        assertEquals(prefix + "response", reply["type"]!!.jsonPrimitive.content)
        assertEquals("conv-1", reply["result"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a repeated ready soon after connecting is not a new app`() = runTest {
        // The app asks every second until it is connected; a repeat in flight must not
        // replace the port it has already taken.
        val page = page()
        page.onPageMessage("""{"kind":"app-ready"}""")
        clockMs += ControlCenterPage.READY_REPEAT_MS - 1

        page.onPageMessage("""{"kind":"app-ready"}""")

        assertEquals(1, kinds().count { it == "connect" })
    }

    @Test
    fun `an app that reloads is connected again, and its old epoch can no longer act`() = runTest {
        val page = page()
        page.onPageMessage("""{"kind":"app-ready"}""")
        clockMs += ControlCenterPage.READY_REPEAT_MS

        page.onPageMessage("""{"kind":"app-ready"}""")
        page.onPageMessage(fromApp("epoch-1"))
        runCurrent()

        assertEquals(listOf("connect", "message", "close", "connect", "message"), kinds())
        assertEquals("epoch-2", toPage[3]["epoch"]!!.jsonPrimitive.content)
        assertTrue("a request on the old epoch reached the chat", sent.isEmpty())
    }

    @Test
    fun `published snapshots reach the connected app`() = runTest {
        val page = page()
        page.publish(snapshot)
        assertTrue("nothing to send before the app is connected", toPage.isEmpty())

        page.onPageMessage("""{"kind":"app-ready"}""")
        page.publish(snapshot.copy(status = ControlCenterAgentStatus.WORKING))

        val event = Json.parseToJsonElement(toPage.last()["data"]!!.jsonPrimitive.content).jsonObject
        assertEquals(prefix + "event", event["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anything else from the page is ignored`() = runTest {
        val page = page()

        listOf("not json", "[]", """{"kind":"navigate","url":"https://evil.example"}""", """{"kind":"message","data":"{}"}""")
            .forEach(page::onPageMessage)
        page.onPageMessage("""{"kind":"message","data":"${"x".repeat(5 * 1024 * 1024)}"}""")

        assertTrue(toPage.isEmpty())
    }

    @Test
    fun `an app whose address has no origin we can pin is never loaded`() = runTest {
        // Our own origin too: the app's sandbox would not hold if it shared it.
        listOf("https://apps_example.net/index.html", ControlCenterPage.PAGE_ORIGIN + "/control-center/host.html").forEach { url ->
            val page = ControlCenterPage(
                app.copy(url = url),
                { snapshot },
                actions,
                backgroundScope,
                toPage = { toPage += Json.parseToJsonElement(it).jsonObject }
            )

            page.onPageMessage("""{"kind":"page-ready"}""")

            assertNull(url, page.appOrigin)
        }
        assertTrue(toPage.isEmpty())
    }

    @Test
    fun `a frame the page's CSP refused is reported, and the app reloaded once`() = runTest {
        // A redirect loop must not reload forever; after one try the bar stays and the user leaves.
        val page = page()
        page.onPageMessage("""{"kind":"page-ready"}""")
        page.onPageMessage("""{"kind":"app-ready"}""")

        page.onPageMessage("""{"kind":"blocked"}""")
        page.onPageMessage("""{"kind":"blocked"}""")

        assertEquals(2, blocks)
        assertEquals(listOf("load", "connect", "message", "close", "load"), kinds())
    }

    @Test
    fun `the main frame shows only our page, and the app's frame only the app's origin`() = runTest {
        // WebView asks about a subframe only for non-http(s) schemes; the CSP covers https.
        val page = page()

        assertTrue(page.allowsNavigation(ControlCenterPage.PAGE_URL, mainFrame = true))
        assertFalse(page.allowsNavigation("https://r-1.apps.example.net/index.html", mainFrame = true))
        assertTrue(page.allowsNavigation("https://r-1.apps.example.net/settings", mainFrame = false))
        assertFalse(page.allowsNavigation("https://evil.example/", mainFrame = false))
        assertFalse(page.allowsNavigation("http://r-1.apps.example.net/index.html", mainFrame = false))
        assertFalse(page.allowsNavigation(ControlCenterPage.PAGE_URL, mainFrame = false))
        assertFalse(page.allowsNavigation("intent://scan/#Intent;scheme=zxing;end", mainFrame = false))
        assertFalse(page.allowsNavigation("data:text/html,hi", mainFrame = false))
    }

    @Test
    fun `messages count as our page's by origin, whatever form the WebView writes it in`() {
        assertTrue(ControlCenterPage.isPageOrigin("https://appassets.androidplatform.net"))
        assertTrue(ControlCenterPage.isPageOrigin("https://appassets.androidplatform.net/"))
        assertFalse(ControlCenterPage.isPageOrigin("http://appassets.androidplatform.net"))
        assertFalse(ControlCenterPage.isPageOrigin("https://appassets.androidplatform.net.evil.example"))
        assertFalse(ControlCenterPage.isPageOrigin("https://r-1.apps.example.net"))
        assertFalse(ControlCenterPage.isPageOrigin("null"))
    }

    @Test
    fun `origins are written the way the browser reports them`() {
        assertEquals("https://a.example.net", ControlCenterApp.originOf("https://A.Example.net/x/index.html"))
        assertEquals("https://a.example.net:8443", ControlCenterApp.originOf("https://a.example.net:8443/index.html"))
        assertNull(ControlCenterApp.originOf("http://a.example.net/index.html"))
        assertNull(ControlCenterApp.originOf("https://[::1]/index.html"))
        assertNull(ControlCenterApp.originOf("not a url"))
    }

    @Test
    fun `the parent page may frame only the app's origin`() {
        val html = ControlCenterPage.hostDocument("https://r-1.apps.example.net", "#ffffff")

        assertTrue(html.contains("frame-src https://r-1.apps.example.net;"))
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("<script src=\"host.js\" defer></script>"))
        // Its own origin, scripts and forms, but no top navigation, popups or downloads.
        assertTrue(html.contains("sandbox=\"allow-scripts allow-same-origin allow-forms\""))
        assertThrows(IllegalArgumentException::class.java) {
            ControlCenterPage.hostDocument("https://a.example.net; script-src *", "#ffffff")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlCenterPage.hostDocument("https://a.example.net", "red; } body { x")
        }
    }
}
