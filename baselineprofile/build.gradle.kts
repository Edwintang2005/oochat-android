plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "ai.openonion.oochat.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        // Macrobenchmark drives the app from a separate process over UiAutomator,
        // which needs API 28+ — well above :app's own minSdk 26.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // EMUI drops the broadcasts Macrobenchmark sends to a freshly-installed
        // target, so the shader-cache reset can't be acknowledged and the run
        // aborts before measuring. profileinstaller is genuinely present —
        // checked in the merged manifest, the DEX and the runtime classpath —
        // so this is the device, not the build. Downgrading it to a warning
        // costs a little cold-start realism on the first iteration only.
        testInstrumentationRunnerArguments["androidx.benchmark.dropShaders.throwOnFailure"] = "false"
    }

    // No build types declared on purpose. The androidx.baselineprofile plugin
    // creates them itself — nonMinified* for profile generation and benchmark*
    // for measurement — and adding a hand-written one here just multiplies out
    // against those, producing variants nothing runs.
    targetProjectPath = ":app"
}

// Producer-side settings. Generation runs on whatever device is plugged in —
// here that's deliberately the emulator, since generation only records *which*
// code ran and an emulator's inflated speed doesn't distort that. Measurement
// is the opposite and must run on the physical device.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId ?: "" }
        )
    }
}
