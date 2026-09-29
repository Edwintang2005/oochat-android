// Top-level build file for ConnectOnion Android Client
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
    // Macrobenchmark + Baseline Profile (see :baselineprofile). 1.4.1 is the
    // last stable — 1.5.0 is still beta. 1.4.x requires Kotlin >= 2.0.0, which
    // this project meets exactly, so don't drop Kotlin below 2.0.21.
    id("com.android.test") version "8.13.2" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
    // Screenshot baseline (see app/src/test/.../screenshot). Pinned to the last
    // release built against AGP 7.3.1 / Kotlin 1.9.22, so its Gradle plugin uses
    // no AGP API newer than this project's 8.2.2 and its artifacts carry no
    // Kotlin metadata this project's 1.9.22 compiler would reject.
    id("io.github.takahirom.roborazzi") version "1.27.0" apply false
}
