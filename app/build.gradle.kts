import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("jacoco")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.github.takahirom.roborazzi")
    // Consumes the profile :baselineprofile generates and packages it into the
    // APK, where profileinstaller (pulled in transitively) installs it.
    id("androidx.baselineprofile")
}

jacoco {
    toolVersion = "0.8.11"
}

/**
 * Release signing, read from a git-ignored `keystore.properties` at the repo
 * root. Absent — which is the normal case for anyone who is not cutting a
 * release — the release build stays unsigned exactly as before, so a clone
 * still builds and CI is unaffected. See `keystore.properties.example`.
 */
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}


ktlint {
    // android() enables Kotlin/Android-specific rules (e.g. Compose
    // function naming) instead of plain-JVM ktlint defaults.
    android.set(true)
    filter {
        exclude("**/generated/**")
    }
}

// Room schema snapshots, captured so a future version bump has a real
// historical schema to diff against when writing a Migration — see
// AppDatabase.kt's exportSchema note. KSP reads this, not the
// annotationProcessorOptions block kapt used.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "ai.openonion.oochat"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.openonion.oochat"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            // Null when keystore.properties is absent, which leaves the APK
            // unsigned rather than failing the build.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // No hand-written benchmark build type: the androidx.baselineprofile
        // plugin derives benchmarkRelease (minified, non-debuggable — what
        // Macrobenchmark measures) and nonMinifiedRelease (what the profile is
        // generated against) from release on its own.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Compose strong skipping (compiler 1.5.4+): lets composables with
        // unstable params — e.g. MessageList's List<ChatItem>/Map<String,Long>
        // — skip recomposition by comparing param instances, so a bubble no
        // longer recomposes on unrelated state changes. Opt-in on 1.5.8.
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=StrongSkipping"
        )
    }

    buildFeatures {
        compose = true
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// The two build types androidx.baselineprofile derives from release inherit
// this module's bare applicationId, and both get installed on the test device
// alongside the app itself. Installing over it is the same database-inheritance
// crash a mismatched schema causes, so give them their own package.
//
// Set here rather than as an applicationIdSuffix in buildTypes: the plugin
// creates these build types after the android block is evaluated, so a
// buildTypes lookup silently matches nothing.
androidComponents {
    val benchmarkPackage = "ai.openonion.oochat.benchmark"
    onVariants(selector().withBuildType("benchmarkRelease")) {
        it.applicationId.set(benchmarkPackage)
    }
    onVariants(selector().withBuildType("nonMinifiedRelease")) {
        it.applicationId.set(benchmarkPackage)
    }
}

// Consumer-side settings for the profile :baselineprofile produces.
baselineProfile {
    // One merged profile for all variants rather than a per-variant copy —
    // there is only one shipping variant here, so per-variant would just be
    // duplicate files to keep in sync.
    mergeIntoMain = true
    // Check the generated profile into source control. CI can't regenerate it
    // (GitHub-hosted runners have no KVM to run an emulator), so the committed
    // file is the only copy a release build will ever see.
    saveInSrc = true
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Ed25519 signing (TweetNaCl implementation)
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // BIP39 mnemonic generation/validation/seed derivation for account recovery phrases
    implementation("cash.z.ecc.android:kotlin-bip39:1.0.9")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.security:security-crypto:1.1.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Async image loading for message attachments
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Drag-to-reorder for AgentListScreen
    implementation("sh.calvin.reorderable:reorderable:2.5.1")

    // QR code scanning for Setup screen's "scan to connect" — ZXing rather
    // than ML Kit so it works fully offline with no Google Play Services
    // dependency (unlike ML Kit's barcode model, which downloads via GMS).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Robolectric: 在纯 JVM 单测里提供够真的 Context/Room/SharedPreferences 影子，
    // 让依赖 Android 框架的类无需真机即可测试。
    testImplementation("org.robolectric:robolectric:4.13")
    // Screenshot baselines. Roborazzi rather than Paparazzi because it renders
    // through the Robolectric runtime this suite already uses, so a snapshot
    // test is an ordinary unit test rather than a second renderer.
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.27.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.27.0")
    testImplementation("androidx.test:core:1.5.0")
    // Lets Compose semantics assertions (assertHeightIsAtLeast,
    // assertTouchHeightIsEqualTo, etc.) run on the JVM under Robolectric
    // instead of only on-device — see TouchTargetComposeTest.
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Opt-in: ./gradlew -Pleakcanary=true :app:installDebug
    //
    // Self-installing and no code changes, which is also the problem — on the
    // team's Huawei test device the process was killed repeatedly while its
    // activity was foreground and no .hprof was ever written, i.e. the vendor
    // watchdog took it mid-dump. Every restart re-ran cold start, and an
    // onboarding gate cannot survive that. It also adds a second launcher icon.
    // Worth having for a deliberate leak hunt, not for every debug install.
    if (providers.gradleProperty("leakcanary").orNull == "true") {
        debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    }

    // Installs the packaged profile at first launch. It already arrives
    // transitively via Compose, but Macrobenchmark's Require mode fails with
    // "profileinstaller library is missing from the target apk" unless it's a
    // direct dependency, so declare it rather than relying on the graph.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // The profile :baselineprofile generates, packaged into this APK.
    baselineProfile(project(":baselineprofile"))
}

// Robolectric 用自己的沙箱类加载器，JDK 9+ 下 JaCoCo 默认看不到这些类；
// includeNoLocationClasses + 排除 jdk.internal.* 是官方推荐组合。
tasks.withType<Test>().configureEach {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// 仅基于 debug 单测的覆盖率报告（不需要设备/仪器）。
// 排除生成代码（R/BuildConfig/Manifest、Compose 合成类、Room 的 *_Impl、di/）。
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates a JaCoCo coverage report from the debug unit tests."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    // Beyond the generated code (R/BuildConfig/Manifest, Compose's synthetic
    // ComposableSingletons/Lambda classes, Room's *_Impl DAOs), this also
    // excludes the hand-written Compose presentation layer — Screens,
    // Wrappers, components/, theme/, navigation/, the markdown renderer.
    // JaCoCo's line coverage only records that a recomposition scope *ran*,
    // not that its rendering or interaction was verified, so counting it
    // either inflates the number with meaningless hits or buries the real
    // business-logic coverage under a layer tested a different way (Compose
    // UI tests under androidTest/). ViewModels are NOT excluded — XViewModel
    // compiles to its own class separate from XScreen.kt, so it stays
    // counted, and it's where the JVM-testable UI-layer logic lives.
    val fileFilter = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/*_Impl*",
        "**/*_Impl$*",
        "**/*\$Lambda$*.*",
        "**/*\$inlined$*.*",
        "**/*ComposableSingletons*.*",
        "**/di/**",
        "**/ui/**/components/**",
        "**/ui/components/**",
        "**/ui/theme/**",
        "**/ui/navigation/**",
        "**/ui/chat/markdown/**",
        "**/*ScreenKt*.class",
        "**/*ScreenWrapper*.class",
    )
    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") {
        exclude(fileFilter)
    }
    sourceDirectories.setFrom(files("$projectDir/src/main/java"))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    })
}

// Roborazzi drives the whole testDebugUnitTest task, so a bare
// `verifyRoborazziDebug` runs the other Robolectric classes with the native
// graphics runtime loaded and segfaults the JVM part-way through. No single
// class does it — it takes the runtime being set up and torn down across many
// of them. Capture is only meaningful for the screenshot classes, so scope the
// task to those.
tasks.withType<Test>().configureEach {
    val roborazziRun = gradle.startParameter.taskNames.any { it.contains("Roborazzi", ignoreCase = true) }
    if (roborazziRun) {
        filter { includeTestsMatching("*ScreenshotTest") }
    }
}
