package ai.openonion.oochat.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

/**
 * Whether the *device* has a network at all — deliberately not whether the
 * agent is reachable, which only [AgentConnection] can know.
 *
 * It exists because the socket is a slow witness. A drop with no FIN (airplane
 * mode, a tunnel) leaves OkHttp's WebSocket open until a ping goes unanswered:
 * with `pingInterval` at [AgentConnection.PING_INTERVAL_SECONDS] that is 15-30s
 * of the app still believing it is connected, and the user seeing nothing.
 * The OS knows within a few hundred milliseconds, so this fills exactly that
 * window and nothing else — connection state stays the socket's to own.
 *
 * An interface, not the [ConnectivityManagerNetworkMonitor] class, so consumers
 * can be tested against a plain flow instead of a system service.
 */
interface NetworkMonitor {
    /** `true` while at least one internet-capable network is up. */
    val isOnline: Flow<Boolean>
}

/**
 * [NetworkMonitor] backed by `ConnectivityManager`. Needs `ACCESS_NETWORK_STATE`
 * (a normal permission — granted at install, no runtime prompt).
 *
 * Registers for `NET_CAPABILITY_INTERNET` rather than `NET_CAPABILITY_VALIDATED`.
 * Validated is the stricter truth, but it lands a beat after a network comes up
 * and flickers off on any captive-portal probe — which would make this report
 * outages that never happened. The coarse signal is the right one here: it only
 * has to answer "did the radio go away", and everything subtler is already the
 * socket's job.
 */
class ConnectivityManagerNetworkMonitor(context: Context) : NetworkMonitor {
    private val appContext = context.applicationContext

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            // No service to ask: assume online rather than accuse the network,
            // so the socket stays the only thing that can report a fault.
            trySend(true)
            close()
            return@callbackFlow
        }

        // Tracked as a set, not a single flag: losing Wi-Fi while cellular is
        // still up delivers onLost and must not read as offline.
        val live = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                live += network
                trySend(true)
            }

            override fun onLost(network: Network) {
                live -= network
                trySend(live.isNotEmpty())
            }
        }

        // Seeded before registering, not after: registration replays onAvailable
        // for networks already up, and a seed sent afterwards could overwrite
        // that with a staler read. Registering with no network up delivers
        // nothing at all, which is the case this seed is really for.
        trySend(manager.hasInternetCapableNetwork())

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, callback)

        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().conflate()

    private fun ConnectivityManager.hasInternetCapableNetwork(): Boolean {
        val active = activeNetwork ?: return false
        val capabilities = getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/**
 * Maps a raw online/offline stream to "should we tell the user they are
 * offline", holding an outage back until it has lasted [graceMs].
 *
 * Asymmetric on purpose. Going offline is debounced because a Wi-Fi-to-cellular
 * handover, a lift, or a lock-screen radio nap all produce a sub-second gap that
 * costs the user nothing — announcing those would train them to ignore the
 * banner. Coming back online is not debounced: the banner is stale the instant
 * it stops being true, and there is no false-positive to guard against in that
 * direction.
 *
 * A pure operator rather than logic inside the ViewModel or the composable, so
 * the timing is testable on virtual time with no Android involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<Boolean>.offlineSustainedFor(graceMs: Long): Flow<Boolean> =
    distinctUntilChanged()
        // mapLatest, so a network that returns inside the grace cancels the
        // pending delay instead of announcing an outage that already ended.
        .mapLatest { online ->
            if (online) false else { delay(graceMs); true }
        }
        .distinctUntilChanged()

/**
 * Long enough to sit out a handover or a radio nap, short enough that the user
 * hears about a real outage well before the socket's own 15-30s ping timeout
 * would have.
 */
const val OFFLINE_GRACE_MS = 2000L
