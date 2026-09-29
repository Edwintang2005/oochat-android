package ai.openonion.oochat.data.local

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SafePreferencesWrapper.resolvePrefs] — the pure
 * encrypted-vs-fallback decision logic extracted from the constructor
 * specifically so it's testable without a real Android Keystore or
 * [android.content.Context], neither of which is available in a plain JVM
 * unit test.
 *
 * Covers the security-relevant behavior this session's audit flagged:
 * `SafePreferencesWrapper` silently falls back to plaintext prefs on
 * Keystore failure — `isSecure()`/`getStatus()` (backed by this same
 * decision) are how a caller (now: SettingsScreen's warning banner) detects
 * that fallback instead of it being invisible.
 */
class SafePreferencesWrapperTest {

    /** Minimal no-op SharedPreferences — a JVM-only fake, no Robolectric needed. */
    private class FakeSharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = throw NotImplementedError()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun `resolvePrefs uses the encrypted prefs and reports secure when creation succeeds`() {
        val encrypted = FakeSharedPreferences()
        val standard = FakeSharedPreferences()

        val (resolved, isSecure) = SafePreferencesWrapper.resolvePrefs(
            createEncrypted = { encrypted },
            createStandard = { standard }
        )

        assertSame(encrypted, resolved)
        assertTrue(isSecure)
    }

    @Test
    fun `resolvePrefs falls back to standard prefs and reports insecure when encrypted creation throws`() {
        val standard = FakeSharedPreferences()

        val (resolved, isSecure) = SafePreferencesWrapper.resolvePrefs(
            createEncrypted = { throw RuntimeException("Keystore unavailable") },
            createStandard = { standard }
        )

        assertSame(standard, resolved)
        assertFalse(isSecure)
    }

    @Test
    fun `resolvePrefs falls back on any exception type, not just a specific one`() {
        val standard = FakeSharedPreferences()

        val (resolved, isSecure) = SafePreferencesWrapper.resolvePrefs(
            createEncrypted = { throw IllegalStateException("corrupted keystore entry") },
            createStandard = { standard }
        )

        assertSame(standard, resolved)
        assertFalse(isSecure)
    }

    @Test
    fun `resolvePrefs does not call createStandard when the encrypted path succeeds`() {
        var standardCalled = false

        SafePreferencesWrapper.resolvePrefs(
            createEncrypted = { FakeSharedPreferences() },
            createStandard = { standardCalled = true; FakeSharedPreferences() }
        )

        assertFalse("createStandard must not be invoked when the encrypted path already succeeded", standardCalled)
    }
}
