package ai.openonion.oochat.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Factory for creating WebSocket instances.
 *
 * This interface allows injecting mock WebSocket implementations for testing.
 */
interface WebSocketFactory {
    fun create(request: Request, listener: WebSocketListener): WebSocket
}

/**
 * Default implementation using OkHttp.
 */
class OkHttpWebSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder().build()
) : WebSocketFactory {
    override fun create(request: Request, listener: WebSocketListener): WebSocket {
        return client.newWebSocket(request, listener)
    }
}
