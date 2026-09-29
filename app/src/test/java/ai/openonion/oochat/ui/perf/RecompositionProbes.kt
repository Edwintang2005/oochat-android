package ai.openonion.oochat.ui.perf

import android.view.View
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.setObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Counts re-executions of a composable's own body without touching the
 * composable — nothing here ships, and no production file grows a counter.
 *
 * The trick is that a `DisposableEffect(key)` is `@NonRestartableComposable`:
 * its `remember` slot lives in the *calling* scope, so Compose compares the
 * key with `equals` exactly once every time that caller's body re-executes,
 * and never otherwise. `ChatScreen` keys one on `LocalLifecycleOwner.current`,
 * so providing this as the lifecycle owner turns [comparisons] into a direct
 * count of `ChatScreen` body executions after the first frame.
 *
 * A silent zero would be indistinguishable from "the probe stopped working",
 * so every test that asserts a zero here must also drive a change that is
 * *supposed* to re-execute the body and assert the count moved. See
 * [ChatScreenRecompositionTest][ai.openonion.oochat.ui.chat.ChatScreenRecompositionTest].
 */
class BodyExecutionProbe : LifecycleOwner {
    private val registry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.RESUMED
    }
    override val lifecycle: Lifecycle get() = registry

    var comparisons: Int = 0
        private set

    override fun equals(other: Any?): Boolean {
        comparisons++
        return this === other
    }

    override fun hashCode(): Int = System.identityHashCode(this)

    /** Number of body executions since [mark], ignoring any constant offset. */
    private var marked = 0
    fun mark() { marked = comparisons }
    fun sinceMark(): Int = comparisons - marked
}

/**
 * Total recompose-scope executions across the whole composition, via the
 * runtime's own [CompositionObserver]. This is the "how much of the screen
 * re-ran" number: one entry per scope that Compose actually executed, so a
 * subtree that skips contributes nothing.
 *
 * The composition has to be reached by reflection because
 * `createComposeRule()` keeps its `Recomposer` private and does not publish
 * it on the view tree (`findViewTreeCompositionContext()` returns null under
 * the rule). [attach] searches the hosting view for a `Composition`-typed
 * field rather than a field *name*, and throws if it finds none — a recorder
 * that silently counted zero would make every bound below it pass.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class RecompositionRecorder {

    private var handle: CompositionObserverHandle? = null

    var passes: Int = 0
        private set
    var scopeExecutions: Int = 0
        private set

    fun attach(composeView: View) {
        val composition = findComposition(composeView)
            ?: error(
                "No Composition found on the view hierarchy above ${composeView.javaClass.name}. " +
                    "The Compose test rule's hosting view changed shape; fix the search rather " +
                    "than letting the recorder report zero."
            )
        handle = composition.setObserver(object : CompositionObserver {
            override fun onBeginComposition(composition: ObservableComposition) { passes++ }
            override fun onScopeEnter(scope: RecomposeScope) { scopeExecutions++ }
            override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
            override fun onScopeExit(scope: RecomposeScope) = Unit
            override fun onEndComposition(composition: ObservableComposition) = Unit
            override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) = Unit
            override fun onScopeDisposed(scope: RecomposeScope) = Unit
        }) ?: error("This Composition does not support observation.")
    }

    fun detach() {
        handle?.dispose()
        handle = null
    }

    fun reset() {
        passes = 0
        scopeExecutions = 0
    }

    private fun findComposition(from: View): Composition? {
        var node: Any? = from
        while (node is View) {
            val classes = generateSequence<Class<*>>(node.javaClass) { it.superclass }
            for (klass in classes) {
                for (field in klass.declaredFields) {
                    if (!Composition::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    (field.get(node) as? Composition)?.let { return it }
                }
            }
            node = node.parent
        }
        return null
    }
}
