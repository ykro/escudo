package app.escudo.android

/**
 * Marks instrumented tests that talk to the deployed backend. They are filtered out unless
 * LIVE_TESTS=1 (env var or `-PLIVE_TESTS=1`), so `connectedDebugAndroidTest` stays offline.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LiveTest
