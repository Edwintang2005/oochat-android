package ai.openonion.oochat

import org.junit.AssumptionViolatedException

/**
 * A test for behaviour that looks unintended, written the way it *should*
 * behave.
 *
 * [block] asserts the intended behaviour and runs every time. While the issue
 * is still there it fails, and the test is reported as skipped with
 * "KNOWN ISSUE: [description]" — visible in every test report, without turning
 * the build red over a bug nobody has fixed yet. Once someone fixes it,
 * [block] passes and this fails the test on purpose: remove the wrapper, and
 * the same assertion becomes the regression test for the fix.
 *
 * Only for behaviour a reviewer would call a bug. If the current behaviour is
 * a decision, test it plainly instead.
 */
inline fun knownIssue(description: String, block: () -> Unit) {
    try {
        block()
    } catch (e: AssumptionViolatedException) {
        throw e
    } catch (e: Throwable) {
        val reason = "KNOWN ISSUE: $description (${e::class.simpleName}: ${e.message})"
        // Gradle's XML report drops a skip's message; stderr is kept beside it.
        System.err.println(reason)
        throw AssumptionViolatedException(reason)
    }
    throw AssertionError(
        "Known issue appears fixed: $description. Remove the knownIssue { } wrapper " +
            "so this test guards the fix."
    )
}
