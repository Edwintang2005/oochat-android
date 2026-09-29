package ai.openonion.oochat.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan guard tests enforcing the layering this refactor established,
 * so a future change can't silently reintroduce the violations already
 * fixed: UI touching repositories/DAOs directly instead of going through
 * [ai.openonion.oochat.di.AppContainer], or `domain/` depending on
 * Android/Compose/wire-protocol types.
 *
 * Deliberately lightweight — plain `import` line matching over `.kt` source
 * text, no new test dependency (e.g. ArchUnit's bytecode-level import
 * checks) and no Robolectric. This only catches violations expressed as an
 * `import` statement, not a fully-qualified reference used without an
 * import — a determined violator could dodge it, but the goal here is
 * catching accidental regressions (an IDE auto-import reaching for the
 * wrong class), which this covers. If that scoping proves too weak in
 * practice, ArchUnit is the natural upgrade.
 *
 * [ai.openonion.oochat.ui.chat.ChatViewModel] still takes
 * `network.AgentDiscoveryService` as a constructor dependency, which the
 * import guard deliberately does not flag: the type is legitimately named
 * there, and the container supplies the instance. What is forbidden is
 * *constructing* one — see the separate guard below. The import guard
 * targets the concrete classes Phase 1/3 extracted `ui/` away from
 * (repository impls, DAOs, `AppDatabase`, `SafePreferencesWrapper`,
 * `AgentConnection`/`WebSocketFactory`), not the whole `network` namespace.
 */
class ArchitectureGuardTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "src/main/java/ai/openonion/oochat")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error(
            "Could not locate src/main/java/ai/openonion/oochat from " +
                "working directory ${System.getProperty("user.dir")} — ArchitectureGuardTest's " +
                "source-scan assumes the Gradle test task's working directory is the app module root."
        )
    }

    private fun ktFilesUnder(relativePackageDir: String): List<File> {
        val dir = File(sourceRoot, relativePackageDir)
        assertTrue("expected directory to exist: $dir", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun importLines(file: File): List<String> =
        file.readLines().filter { it.trimStart().startsWith("import ") }

    // ── UI must not bypass the composition root ─────────────────────

    private val forbiddenUiImports = listOf(
        Regex("""^import ai\.openonion\.oochat\.data\.repository\.\w+Impl$"""),
        Regex("""^import ai\.openonion\.oochat\.data\.local\.db\.dao\."""),
        Regex("""^import ai\.openonion\.oochat\.data\.local\.db\.AppDatabase$"""),
        Regex("""^import ai\.openonion\.oochat\.data\.local\.SafePreferencesWrapper$"""),
        Regex("""^import ai\.openonion\.oochat\.network\.AgentConnection$"""),
        Regex("""^import ai\.openonion\.oochat\.network\.\w*WebSocketFactory$""")
    )

    @Test
    fun `ui layer does not import repository implementations, DAOs, or raw connection classes`() {
        val violations = mutableListOf<String>()
        for (file in ktFilesUnder("ui")) {
            for (line in importLines(file)) {
                if (forbiddenUiImports.any { it.containsMatchIn(line.trim()) }) {
                    violations += "${file.relativeTo(sourceRoot)}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "ui/ must go through di.AppContainer instead of importing these directly:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ── domain/ must stay platform- and protocol-agnostic ───────────

    private val forbiddenDomainImports = listOf(
        Regex("""^import android\."""),
        Regex("""^import androidx\.compose\."""),
        Regex("""^import ai\.openonion\.oochat\.data\.protocol\.""")
    )

    @Test
    fun `domain layer does not import Android, Compose, or wire-protocol types`() {
        val violations = mutableListOf<String>()
        for (file in ktFilesUnder("domain")) {
            for (line in importLines(file)) {
                if (forbiddenDomainImports.any { it.containsMatchIn(line.trim()) }) {
                    violations += "${file.relativeTo(sourceRoot)}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "domain/ must stay platform-agnostic; found:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ── data/ and domain/ must not depend back on ui/ ───────────────

    @Test
    fun `data and domain layers do not import ui`() {
        val uiImport = Regex("""^import ai\.openonion\.oochat\.ui\.""")
        val violations = mutableListOf<String>()
        for (packageDir in listOf("data", "domain")) {
            for (file in ktFilesUnder(packageDir)) {
                for (line in importLines(file)) {
                    if (uiImport.containsMatchIn(line.trim())) {
                        violations += "${file.relativeTo(sourceRoot)}: ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "data/ and domain/ must not depend on ui/ (backward dependency); found:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ── UI must not build its own shared services ───────────────────

    private val discoveryConstruction = Regex("""\bAgentDiscoveryService\(\)""")

    /**
     * `AgentDiscoveryService` owns an `OkHttpClient`, so a second one is a
     * second connection pool and dispatcher thread pool. The container holds
     * the shared instance; a `ui/` file constructing its own is the
     * regression this catches (import-based scanning would miss it — the old
     * ChatViewModel default used a fully-qualified name, no import line).
     */
    @Test
    fun `ui layer does not construct its own AgentDiscoveryService`() {
        val violations = ktFilesUnder("ui")
            .filter { discoveryConstruction.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).toString() }
        assertTrue(
            "ui/ must take AgentDiscoveryService from di.AppContainer, not build a second one:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ── Guard-the-guard: prove these assertions can actually fail ───

    @Test
    fun `forbidden-import regexes actually match a deliberately bad import line`() {
        // This doesn't touch any real source file — it's a sanity check
        // that the regexes above are not accidentally unmatchable (e.g. a
        // typo'd package prefix that would make every real test vacuously
        // pass no matter what violations exist).
        assertTrue(forbiddenUiImports.any {
            it.containsMatchIn("import ai.openonion.oochat.data.repository.AgentRepositoryImpl")
        })
        assertTrue(forbiddenUiImports.any {
            it.containsMatchIn("import ai.openonion.oochat.data.local.db.dao.AgentDao")
        })
        assertTrue(forbiddenDomainImports.any {
            it.containsMatchIn("import androidx.compose.runtime.Composable")
        })
        assertTrue(forbiddenDomainImports.any {
            it.containsMatchIn("import ai.openonion.oochat.data.protocol.SessionState")
        })
        assertTrue(discoveryConstruction.containsMatchIn("val x = AgentDiscoveryService()"))
    }
}
