// Gradle init script for GitLab CI only - .gitlab-ci.yml copies it into
// GRADLE_USER_HOME/init.d, so local builds never see it.
//
// The runners are 2 vCPU / 4 GB machines with no swap. Left alone, the test JVM
// sizes its heap from the machine, and a test task that prints nothing until it
// ends looks exactly like one that has hung. So: cap the heap explicitly, and
// report each test class as it finishes.
allprojects {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "1g"

        addTestListener(object : TestListener {
            private var classStarted = System.currentTimeMillis()

            override fun beforeSuite(suite: TestDescriptor) {
                if (suite.className != null) classStarted = System.currentTimeMillis()
            }

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                val className = suite.className ?: return
                val seconds = (System.currentTimeMillis() - classStarted) / 1000
                println(
                    "[tests] ${className.substringAfterLast('.')}: ${result.testCount} run, " +
                        "${result.failedTestCount} failed, ${result.skippedTestCount} skipped (${seconds}s)"
                )
            }

            override fun beforeTest(testDescriptor: TestDescriptor) {}

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        })
    }
}
