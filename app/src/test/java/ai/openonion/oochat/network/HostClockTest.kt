package ai.openonion.oochat.network

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [HostClock]: the device clock, corrected by the server's `Date` header (W16AAPPLE-90).
 * Robolectric only because FileLogger mirrors to android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostClockTest {

    private val device = 1_790_000_000_000L
    private val tenMinutes = 10 * 60_000L

    private fun upgrade(dateMillis: Long? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("wss://fake.example.com/ws").build())
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .apply { if (dateMillis != null) header("Date", httpDate(dateMillis)) }
            .build()

    private fun httpDate(millis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

    @Test
    fun `no reading means no correction`() {
        assertEquals(0L, HostClock.offsetFrom(serverMillis = null, deviceMillis = device))
    }

    @Test
    fun `a slow device clock is corrected forward and a fast one back`() {
        assertEquals(tenMinutes, HostClock.offsetFrom(device + tenMinutes, device))
        assertEquals(-tenMinutes, HostClock.offsetFrom(device - tenMinutes, device))
    }

    @Test
    fun `an offset up to the bound is applied, and one past it is ignored`() {
        val bound = HostClock.MAX_OFFSET_MS
        assertEquals(bound, HostClock.offsetFrom(device + bound, device))
        assertEquals(-bound, HostClock.offsetFrom(device - bound, device))
        assertEquals(0L, HostClock.offsetFrom(device + bound + 1_000, device))
        assertEquals(0L, HostClock.offsetFrom(device - bound - 1_000, device))
    }

    @Test
    fun `signed time is the device clock plus the server's correction`() {
        val clock = HostClock(deviceMillis = { device })
        assertEquals("before any socket, the device clock", device / 1000, clock.nowSeconds())

        clock.observe(upgrade(dateMillis = device + tenMinutes))

        assertEquals((device + tenMinutes) / 1000, clock.nowSeconds())
    }

    @Test
    fun `each socket replaces the last reading, and one without a Date goes back to the device clock`() {
        val clock = HostClock(deviceMillis = { device })
        clock.observe(upgrade(dateMillis = device + tenMinutes))

        clock.observe(upgrade(dateMillis = null))

        assertEquals(0L, clock.offsetMillis)
        assertEquals(device / 1000, clock.nowSeconds())
    }

    @Test
    fun `a Date past the bound leaves the device clock in charge`() {
        val clock = HostClock(deviceMillis = { device })

        clock.observe(upgrade(dateMillis = device + 2 * 60 * 60_000L))

        assertEquals(device / 1000, clock.nowSeconds())
    }

    @Test
    fun `an unreadable Date is no reading`() {
        val clock = HostClock(deviceMillis = { device })
        val garbled = upgrade().newBuilder().header("Date", "yesterday-ish").build()

        clock.observe(garbled)

        assertEquals(0L, clock.offsetMillis)
    }
}
