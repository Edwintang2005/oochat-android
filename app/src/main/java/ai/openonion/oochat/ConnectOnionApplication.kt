package ai.openonion.oochat

import ai.openonion.oochat.di.AppContainer
import ai.openonion.oochat.di.DefaultAppContainer
import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/**
 * Named `ConnectOnionApplication` (not `ConnectOnionApp`) to avoid colliding
 * with the top-level `@Composable fun ConnectOnionApp()` in ConnectOnionApp.kt.
 */
class ConnectOnionApplication : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { DefaultAppContainer(this) }

    // Started-activity count, the standard dependency-free way to know
    // whether the app is visible (lifecycle-process/ProcessLifecycleOwner
    // is not a dependency here). A count, not a flag, so it survives one
    // activity starting before another has finished stopping — this app
    // only ever has MainActivity, but a count is the correct primitive.
    private var startedActivityCount = 0
    val isInForeground: Boolean
        get() = startedActivityCount > 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivityCount++ }
            override fun onActivityStopped(activity: Activity) { startedActivityCount-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Coil's singleton loader. Called lazily on the first image load, not at
     * startup, so this costs the cold-start path nothing.
     *
     * Coil's own default memory cache is 20% of the app's memory class (15% on
     * low-RAM devices) — the largest single retained allocation in the app, and
     * it competes for heap with the attachment path's own decodes. Chat images
     * are re-fetched cheaply from disk or from Room, so trading cache depth for
     * headroom is the right way round here.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.10).build() }
            // Coil applies RGB_565 only when the decoded source is a JPEG (see
            // BitmapFactoryDecoder.configureConfig), so no image with an alpha
            // channel is ever downgraded — it just halves bytes-per-pixel for
            // camera and gallery photos, which is most of what gets attached.
            .allowRgb565(true)
            .build()
}
