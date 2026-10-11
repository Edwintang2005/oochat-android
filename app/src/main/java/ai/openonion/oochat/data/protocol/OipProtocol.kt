package ai.openonion.oochat.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The OIP protocol descriptor: which version of the ConnectOnion browser
 * protocol a peer speaks. The client declares it at the top level of every
 * CONNECT, and the Host advertises its own in CONNECTED (and in the ERROR it
 * sends when it rejects ours).
 *
 * Wire shape verified against ConnectOnion's network/host/protocol.py
 * (`oip_descriptor()` / `supports_oip()`) and connectonion-react's
 * src/connect/protocol.ts (`OIP_PROTOCOL`), which sends exactly
 * `{"name":"oip","version":"0.1","min_version":"0.1","max_version":"0.1"}`.
 * The Host compares only [name] and [version]; [minVersion]/[maxVersion]
 * describe the rolling compatibility window the client can read.
 */
@Serializable
data class ProtocolDescriptor(
    val name: String,
    val version: String,
    @SerialName("min_version") val minVersion: String,
    @SerialName("max_version") val maxVersion: String
)

/**
 * The OIP version this client implements. One place to bump when the Host
 * moves: a Host that does not support [VERSION] answers CONNECT with a
 * non-retryable ERROR (code -32010, "Unsupported OIP protocol").
 */
object OipProtocol {
    const val NAME = "oip"
    const val VERSION = "0.1"
    const val MIN_VERSION = "0.1"
    const val MAX_VERSION = "0.1"

    /**
     * The ERROR `code` the Host sends when it does not support [VERSION]:
     * `{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol",
     * "retryable":false,"protocol":{...}}` (ws_router/connect.py). A string
     * because [ServerEvent.code] reads every code as its text.
     */
    const val UNSUPPORTED_VERSION_CODE = "-32010"

    val DESCRIPTOR = ProtocolDescriptor(
        name = NAME,
        version = VERSION,
        minVersion = MIN_VERSION,
        maxVersion = MAX_VERSION
    )
}
