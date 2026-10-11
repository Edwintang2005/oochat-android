package ai.openonion.oochat.network

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import okhttp3.Response
import kotlin.math.abs

/**
 * This device's clock, corrected by what the server's clock said when the socket opened.
 *
 * The Host refuses a signature whose timestamp is more than 300 s from its own clock
 * (auth.py `_authenticate_signed`), and since W16AAPPLE-16 every command is signed, so a phone
 * whose clock is a few minutes off had every CONNECT and every message refused. The WebSocket
 * upgrade's HTTP `Date` header says what time the server thinks it is; the difference is applied
 * to every timestamp signed on that socket (W16AAPPLE-90).
 *
 * Bounded by [MAX_OFFSET_MS]. On the relay path the header comes from the relay, the party the
 * signed-command gate guards against, and the Host only records a signature as used once it has
 * passed the expiry check. A relay that moved this clock forward could therefore hold a command
 * and deliver it up to that much later. The bound keeps that window to what a mis-set phone
 * plausibly needs; past it the device clock is used, and the Host's refusal reaches the user as
 * advice to check the date and time (ServerErrorText.CLOCK_WRONG).
 *
 * @param deviceMillis the device clock; a seam for tests.
 */
internal class HostClock(private val deviceMillis: () -> Long = System::currentTimeMillis) {

    /** Server time minus device time, in milliseconds, as of the last socket opened. */
    @Volatile var offsetMillis: Long = 0L
        private set

    /**
     * Take a newly opened socket's reading. Every socket replaces the last one's: a server that
     * sends no usable `Date` puts this back on the device clock rather than keeping a reading taken
     * from some other connection.
     */
    fun observe(response: Response) {
        val serverMillis = runCatching { response.headers.getDate("Date")?.time }.getOrNull()
        offsetMillis = offsetFrom(serverMillis, deviceMillis())
    }

    /** Unix time in seconds, as the server sees it — what every signed timestamp should carry. */
    fun nowSeconds(): Long = (deviceMillis() + offsetMillis) / 1000

    companion object {
        /** The largest correction applied; see the class doc for why there is one at all. */
        const val MAX_OFFSET_MS = 30 * 60 * 1000L

        /** Offsets smaller than this are not worth a log line; `Date` only has whole seconds anyway. */
        private const val NOTEWORTHY_OFFSET_MS = 5_000L

        /**
         * The correction for a server whose clock read [serverMillis] when the device's read
         * [deviceMillis]: zero when there was no reading, or when it is past [MAX_OFFSET_MS].
         */
        internal fun offsetFrom(serverMillis: Long?, deviceMillis: Long): Long {
            if (serverMillis == null) return 0L
            val offset = serverMillis - deviceMillis
            if (abs(offset) > MAX_OFFSET_MS) {
                FileLogger.w(
                    LogTags.AGENT_CONN,
                    "Server clock is ${offset / 1000}s from this device's, past the ${MAX_OFFSET_MS / 1000}s bound — not correcting"
                )
                return 0L
            }
            if (abs(offset) >= NOTEWORTHY_OFFSET_MS) {
                FileLogger.i(LogTags.AGENT_CONN, "Device clock is ${-offset / 1000}s from the server's — signing with the server's time")
            }
            return offset
        }
    }
}
