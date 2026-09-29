package ai.openonion.oochat.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * Cold-start timings, with and without the Baseline Profile, so the profile's
 * contribution is a measured number rather than an assumption.
 *
 * **Run this on the physical device, not the emulator.** An emulator's CPU is
 * host-scheduled and its storage is the host's SSD, which is exactly the cost
 * a Baseline Profile exists to remove — measuring there understates the
 * profile's benefit toward zero and would argue against doing the work at all.
 *
 * Two things to read the numbers against:
 *  - The app holds a 450ms splash and then runs ~800ms of crossfade and nav
 *    animation. `timeToInitialDisplay` does not include that, so a win here is
 *    real but partly invisible to a user until those holds are addressed.
 *  - The device is not rooted, so clocks can't be locked. Variance is high;
 *    read the median across [ITERATIONS], never a single run.
 */
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * No profile, no JIT warmup — the worst case, and the honest floor.
     *
     * Disabled: `CompilationMode.None()` resets compilation via
     * `cmd package compile --reset`, and that hangs indefinitely on both
     * devices available here — the Huawei sits idle at 768% CPU free with no
     * dex2oat running, and the emulator dies on a perfetto teardown instead.
     * Because Macrobenchmark only writes results once the whole instrumentation
     * run finishes, one stuck test also withholds the numbers from the tests
     * that did pass, which is why this can't just be left failing.
     *
     * The Require arm below is the more important number anyway: it's what a
     * user actually gets. Re-enable this once there's a device whose
     * compilation reset works, and get the floor to compare against.
     */
    @Ignore("CompilationMode.None() hangs on both available devices; see KDoc")
    @Test
    fun startupNoCompilation() = startup(CompilationMode.None())

    /**
     * Cold start in whatever compilation state installing the APK leaves —
     * i.e. the packaged profile as dexopt applied it, nothing forced.
     *
     * `Ignore()` because every mode that *manipulates* compilation hangs on
     * the only physical device available here. `None()` and `Partial()` both
     * park indefinitely inside `cmd package compile` with no dex2oat running
     * and the CPU ~680% idle, and `Partial(Require)` additionally fails
     * waiting on profileinstaller's acknowledgement broadcast — which EMUI
     * drops for a freshly-installed package. The library is genuinely present;
     * that was verified in the merged manifest, the DEX, and the runtime
     * classpath before concluding it was the device.
     *
     * What this buys: a real cold-start number on real hardware, which is what
     * later phases need to measure against. What it does not buy: any claim
     * about the Baseline Profile's own contribution, which needs a device
     * whose compilation control works.
     */
    @OptIn(androidx.benchmark.macro.ExperimentalMacrobenchmarkApi::class)
    @Test
    fun startupAsInstalled() = startup(CompilationMode.Ignore())

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = targetAppId,
        metrics = listOf(StartupTimingMetric()),
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        compilationMode = mode,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        // Without this the measure block can return before the Compose splash
        // has handed off, so the two compilation modes would be compared over
        // different amounts of work.
        device.wait(Until.hasObject(By.pkg(targetAppId).depth(0)), 5_000)
    }

    private val targetAppId: String
        get() = InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId not set — see the androidComponents block in build.gradle.kts")

    private companion object {
        // High enough for a usable median on a device whose clocks can't be
        // locked; low enough that a full run stays under a few minutes.
        const val ITERATIONS = 15
    }
}
