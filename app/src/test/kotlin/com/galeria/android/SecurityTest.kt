package com.galeria.android

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {
    @After
    fun lockSession() = SecuritySession.lockAll()

    @Test
    fun passwordHashVerifiesOnlyTheSamePassword() {
        val encoded = PasswordHashing.encode("1234", iterations = 1_000)
        assertTrue(PasswordHashing.verify("1234", encoded))
        assertFalse(PasswordHashing.verify("12345", encoded))
        assertFalse(PasswordHashing.verify("", encoded))
    }

    @Test
    fun passwordIsNeverStoredInPlainText() {
        val encoded = PasswordHashing.encode("segredo", iterations = 1_000)
        assertFalse(encoded.contains("segredo"))
        assertTrue(encoded.startsWith("pbkdf2$1000$"))
    }

    @Test
    fun samePasswordGetsADifferentSaltEachTime() {
        assertNotEquals(PasswordHashing.encode("1234", iterations = 1_000), PasswordHashing.encode("1234", iterations = 1_000))
    }

    @Test
    fun malformedHashesNeverVerify() {
        assertFalse(PasswordHashing.verify("1234", null))
        assertFalse(PasswordHashing.verify("1234", "1234"))
        assertFalse(PasswordHashing.verify("1234", "pbkdf2\$x\$00\$00"))
        assertFalse(PasswordHashing.verify("1234", "pbkdf2\$1000\$zz\$00"))
        assertFalse(PasswordHashing.verify("1234", "md5\$1000\$00\$00"))
    }

    @Test
    fun shortPasswordsAreRejected() {
        assertFalse(PasswordHashing.isAcceptable("123"))
        assertTrue(PasswordHashing.isAcceptable("1234"))
    }

    @Test
    fun protectionsNeedAnAppPassword() {
        val prefs = FakePreferences()
        prefs.edit().putBoolean(GalleryPreferences.LOCK_ENTIRE_APP, true)
            .putBoolean(GalleryPreferences.LOCK_HIDDEN_ITEMS, true)
            .putBoolean(GalleryPreferences.LOCK_FILE_OPERATIONS, true).apply()
        val store = SecurityStore(prefs)
        assertFalse(store.lockEntireApp)
        assertFalse(store.lockHiddenItems)
        assertFalse(store.lockFileOperations)

        store.setAppPassword("4321")
        assertTrue(store.hasAppPassword)
        assertTrue(store.lockEntireApp)
        assertTrue(store.verifyAppPassword("4321"))
        assertFalse(store.verifyAppPassword("1234"))
    }

    @Test
    fun removingTheAppPasswordTurnsEveryProtectionOff() {
        val prefs = FakePreferences()
        val store = SecurityStore(prefs)
        store.setAppPassword("4321")
        prefs.edit().putBoolean(GalleryPreferences.LOCK_HIDDEN_ITEMS, true).apply()
        store.clearAppPassword()
        assertFalse(store.hasAppPassword)
        assertFalse(prefs.getBoolean(GalleryPreferences.LOCK_HIDDEN_ITEMS, true))
        store.setAppPassword("9999")
        assertFalse("a new password must not bring old locks back", store.lockHiddenItems)
    }

    @Test
    fun sessionLocksAgainOnlyAfterTheGracePeriod() {
        SecuritySession.appUnlocked = true
        SecuritySession.unlockFolder("DCIM/Privado")
        SecuritySession.onBackground(1_000L)
        SecuritySession.onForeground(1_000L + SecuritySession.GRACE_MS)
        assertTrue(SecuritySession.appUnlocked)
        assertTrue(SecuritySession.isFolderUnlocked("DCIM/Privado"))

        SecuritySession.onBackground(100_000L)
        SecuritySession.onForeground(100_001L + SecuritySession.GRACE_MS)
        assertFalse(SecuritySession.appUnlocked)
        assertFalse(SecuritySession.isFolderUnlocked("DCIM/Privado"))
    }

    @Test
    fun secureFolderCoversItsSubfoldersButNotSimilarNames() {
        val keys = setOf("Pictures/Privado")
        assertTrue(SecurityRules.insideSecureFolder("Pictures/Privado", keys))
        assertTrue(SecurityRules.insideSecureFolder("Pictures/Privado/2026", keys))
        assertFalse(SecurityRules.insideSecureFolder("Pictures/Privado2", keys))
        assertFalse(SecurityRules.insideSecureFolder(null, keys))
    }

    @Test
    fun graceExpiresStrictlyAfterTheLimit() {
        assertFalse(SecurityRules.expired(0L, 30_000L, 30_000L))
        assertTrue(SecurityRules.expired(0L, 30_001L, 30_000L))
        assertEquals(30_000L, SecuritySession.GRACE_MS)
    }
}

/** In-memory SharedPreferences for JVM tests. */
internal class FakePreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach(values::remove)
            values.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}
