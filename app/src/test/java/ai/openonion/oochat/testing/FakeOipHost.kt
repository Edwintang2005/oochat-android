package ai.openonion.oochat.testing

import ai.openonion.oochat.network.WebSocketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** One socket the app opened. Once [dead], the far end never hears from it again. */
class FakeOipSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var opened = false
    @Volatile var dead = false

    /**
     * The link has gone silent with no FIN (the phone lost its network): the
     * socket still looks open at both ends, but nothing sent either way arrives.
     */
    @Volatile var unreachable = false
    override fun request(): Request = request
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean {
        if (dead) return false
        sent += text
        return true
    }
    override fun send(bytes: ByteString): Boolean = !dead

    /** The app closing a socket it is done with: nothing more travels on it. */
    override fun close(code: Int, reason: String?): Boolean {
        dead = true
        return true
    }
    override fun cancel() {
        dead = true
    }
}

/**
 * The Host's side of OIP, reduced to what resume depends on: answer CONNECT,
 * record INPUT, and keep the running turn's events so a reattaching client can
 * be replayed from its cursor.
 *
 * It replays the way `connectonion`'s does: each turn has its own event buffer
 * (a fresh `WebSocketIO` per INPUT), and a CONNECT for a `running` session
 * restarts forwarding just after `last_msg_id`, or from the start of the turn
 * when that id is absent or unknown (`WebSocketIO.position_after`). A turn
 * keeps running when the client's socket goes, as it does in
 * `ws_router/session.py`.
 *
 * Open so a test can put something in front of it, such as a relay.
 */
open class FakeOipHost : WebSocketFactory {
    /**
     * Frames reach the app on one background thread, in order, as OkHttp's
     * reader thread delivers them. Robolectric's test thread is the main
     * thread, and the app rightly refuses to touch Room from there.
     */
    private val reader = Executors.newSingleThreadExecutor()
    val sockets = CopyOnWriteArrayList<FakeOipSocket>()
    val connects = CopyOnWriteArrayList<JsonObject>()
    val inputs = CopyOnWriteArrayList<JsonObject>()
    private val answered = mutableSetOf<String>()

    /** This turn's events, in order — the Host's `_msgs_from_agent`. */
    private val turnEvents = mutableListOf<String>()
    var running = false
        private set

    /** The transcript the Host holds once the turn has finished. */
    var finishedSession: String? = null

    override fun create(request: Request, listener: WebSocketListener): WebSocket =
        FakeOipSocket(request, listener).also { sockets += it }

    protected val live: FakeOipSocket? get() = sockets.lastOrNull { !it.dead }

    /** Open new sockets and answer every CONNECT and INPUT not yet seen. */
    fun pump() {
        for (socket in sockets) {
            if (socket.dead || socket.unreachable) continue
            if (!socket.opened) {
                socket.opened = true
                open(socket)
                if (socket.dead) continue
            }
            for (text in socket.sent) {
                if (!answered.add(text)) continue
                receive(socket, Json.parseToJsonElement(text).jsonObject)
                if (socket.dead) break
            }
        }
    }

    /** The upgrade succeeds. */
    protected open fun open(socket: FakeOipSocket) {
        deliver { socket.listener.onOpen(socket, switchingProtocols(socket.request())) }
    }

    /** A frame from the app reached whatever sits in front of the Host; by default, the Host itself. */
    protected open fun receive(socket: FakeOipSocket, frame: JsonObject) {
        when (frame.string("type")) {
            "CONNECT" -> answerConnect(socket, frame)
            "INPUT" -> {
                inputs += frame
                turnEvents.clear()
                running = true
            }
        }
    }

    protected fun answerConnect(socket: FakeOipSocket, frame: JsonObject) {
        connects += frame
        val sid = frame.string("session_id") ?: "host-minted-${connects.size}"
        val finished = finishedSession
        val reply = when {
            running -> """{"type":"CONNECTED","session_id":"$sid","status":"running"}"""
            // The turn ended while the client was gone: the Host's copy is
            // newer, so it sends it (merge_sessions → server_newer).
            finished != null ->
                """{"type":"CONNECTED","session_id":"$sid","status":"connected",""" +
                    """"server_newer":true,"session":$finished}"""
            else -> """{"type":"CONNECTED","session_id":"$sid","status":"new"}"""
        }
        deliver(socket, reply)
        if (running) {
            // resume_forwarding: start just past last_msg_id, else replay all.
            val cursor = frame.string("last_msg_id")
            val start = turnEvents.indexOfFirst { idOf(it) == cursor }.let { if (it < 0) 0 else it + 1 }
            turnEvents.drop(start).forEach { deliver(socket, it) }
        }
    }

    /** The agent emits [frame]: buffered for replay, forwarded if anyone is attached. */
    fun emit(frame: String) {
        turnEvents += frame
        live?.let { deliver(it, frame) }
    }

    /** OUTPUT: the turn is over and its transcript becomes the session's. */
    fun finish(output: String, session: String) {
        running = false
        finishedSession = session
        live?.let { deliver(it, output) }
    }

    /** The turn ends with nobody attached to hear it. */
    fun finishUnheard(session: String) {
        running = false
        finishedSession = session
    }

    /** The socket fails as a lost network does: no close frame, just an error. */
    fun drop(socket: FakeOipSocket) {
        socket.dead = true
        deliver { socket.listener.onFailure(socket, java.io.IOException("Software caused connection abort"), null) }
    }

    /** How a frame looks by the time it reaches the app on [socket]. */
    protected open fun onTheWire(socket: FakeOipSocket, frame: String): String = frame

    protected fun deliver(socket: FakeOipSocket, frame: String) {
        if (socket.unreachable) return
        deliver { socket.listener.onMessage(socket, onTheWire(socket, frame)) }
    }

    protected fun deliver(call: () -> Unit) {
        reader.execute(call)
    }

    fun shutdown() {
        reader.shutdownNow()
    }

    private fun idOf(frame: String): String? =
        Json.parseToJsonElement(frame).jsonObject.string("id")

    protected fun switchingProtocols(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    companion object {
        fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
