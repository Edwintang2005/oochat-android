package ai.openonion.oochat.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records which classes and methods the startup path touches, so ART can
 * AOT-compile them at install time instead of interpreting and JIT-ing them on
 * the user's first launch.
 *
 * This is the only thing a Baseline Profile fixes. It does not reduce how often
 * a composable recomposes or how much the app allocates — those need real code
 * changes, tracked separately in docs/performance-baseline.md.
 *
 * Runs on an emulator, not a phone: generation only records *which* code ran,
 * so the host's inflated speed is irrelevant here. Measurement is the opposite
 * and lives in [StartupBenchmark].
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Cold launch through to first usable frame.
     *
     * Deliberately stops at the splash/first-content handoff rather than
     * driving into a conversation: the app opens a WebSocket to the relay on
     * launch, and anything past this point would make the recorded profile
     * depend on whether the network answered.
     */
    @Test
    fun startup() = rule.collect(
        packageName = targetAppId,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // The Compose splash holds for MIN_SPLASH_MS (450ms) before the nav
        // graph exists at all, so idling immediately would stop recording
        // before NavHost, LoadingScreen and their ViewModels are ever touched.
        device.wait(Until.hasObject(By.pkg(targetAppId).depth(0)), 5_000)
        device.waitForIdle()
    }

    private val targetAppId: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId not set — check the androidComponents block in build.gradle.kts")
}
