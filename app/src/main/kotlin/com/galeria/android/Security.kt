package com.galeria.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing for the app lock and secure folders. Only a salted PBKDF2 hash is stored;
 * the password itself never leaves memory. Hex keeps the format testable on the JVM.
 */
internal object PasswordHashing {
    const val MIN_LENGTH = 4
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    fun isAcceptable(password: String): Boolean = password.length >= MIN_LENGTH

    fun encode(password: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String =
        "pbkdf2\$$iterations\$${salt.toHex()}\$${derive(password, salt, iterations).toHex()}"

    fun verify(password: String, encoded: String?): Boolean {
        val parts = encoded?.split('$') ?: return false
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = parts[2].fromHex() ?: return false
        val expected = parts[3].fromHex() ?: return false
        return MessageDigest.isEqual(expected, derive(password, salt, iterations))
    }

    private fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}

/** What is protected and with which password. Pure storage; asking the user is in [SecurityGate]. */
internal class SecurityStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE))

    val hasAppPassword: Boolean get() = prefs.contains(GalleryPreferences.APP_PASSWORD)

    fun setAppPassword(password: String) {
        prefs.edit().putString(GalleryPreferences.APP_PASSWORD, PasswordHashing.encode(password)).apply()
    }

    fun verifyAppPassword(password: String): Boolean =
        PasswordHashing.verify(password, prefs.getString(GalleryPreferences.APP_PASSWORD, null))

    /** Removing the app password also turns off every protection that depends on it. */
    fun clearAppPassword() {
        prefs.edit()
            .remove(GalleryPreferences.APP_PASSWORD)
            .putBoolean(GalleryPreferences.LOCK_ENTIRE_APP, false)
            .putBoolean(GalleryPreferences.LOCK_HIDDEN_ITEMS, false)
            .putBoolean(GalleryPreferences.LOCK_FILE_OPERATIONS, false)
            .apply()
    }

    val lockEntireApp: Boolean get() = hasAppPassword && prefs.getBoolean(GalleryPreferences.LOCK_ENTIRE_APP, false)
    val lockHiddenItems: Boolean get() = hasAppPassword && prefs.getBoolean(GalleryPreferences.LOCK_HIDDEN_ITEMS, false)
    val lockFileOperations: Boolean get() = hasAppPassword && prefs.getBoolean(GalleryPreferences.LOCK_FILE_OPERATIONS, false)

    private fun folders(): JSONObject =
        runCatching { JSONObject(prefs.getString(GalleryPreferences.SECURE_FOLDERS, null) ?: "{}") }.getOrDefault(JSONObject())

    /** Album keys protected by their own password. */
    fun secureFolderKeys(): Set<String> = folders().keys().asSequence().toSet()

    fun isSecureFolder(albumKey: String?): Boolean = albumKey != null && folders().has(albumKey)

    fun setFolderPassword(albumKey: String, password: String) {
        val next = folders().put(albumKey, PasswordHashing.encode(password))
        prefs.edit().putString(GalleryPreferences.SECURE_FOLDERS, next.toString()).apply()
    }

    fun verifyFolderPassword(albumKey: String, password: String): Boolean =
        PasswordHashing.verify(password, folders().optString(albumKey, null))

    fun removeFolder(albumKey: String) {
        val next = folders().apply { remove(albumKey) }
        val recovery = recoveries().apply { remove(albumKey) }
        prefs.edit()
            .putString(GalleryPreferences.SECURE_FOLDERS, next.toString())
            .putString(GalleryPreferences.SECURE_FOLDER_RECOVERY, recovery.toString())
            .apply()
    }

    private fun recoveries(): JSONObject =
        runCatching { JSONObject(prefs.getString(GalleryPreferences.SECURE_FOLDER_RECOVERY, null) ?: "{}") }.getOrDefault(JSONObject())

    fun hasRecovery(albumKey: String): Boolean = recoveries().has(albumKey)

    /** The stored question: "preset:N" for one of the ready questions, otherwise the user's own text. */
    fun recoveryQuestion(albumKey: String): String? = recoveries().optJSONObject(albumKey)?.optString("question")

    fun setRecovery(albumKey: String, question: String, answer: String) {
        val entry = JSONObject().put("question", question).put("answer", PasswordHashing.encode(RecoveryRules.normalize(answer)))
        prefs.edit().putString(GalleryPreferences.SECURE_FOLDER_RECOVERY, recoveries().put(albumKey, entry).toString()).apply()
    }

    fun verifyRecovery(albumKey: String, answer: String): Boolean {
        val stored = recoveries().optJSONObject(albumKey)?.optString("answer") ?: return false
        return PasswordHashing.verify(RecoveryRules.normalize(answer), stored)
    }
}

/**
 * What the user already unlocked in this visit. Everything locks again when the app stays in the
 * background longer than [GRACE_MS], so a quick trip to the share sheet or a permission screen
 * does not ask for the password again.
 */
internal object SecuritySession {
    const val GRACE_MS = 30_000L

    var appUnlocked = false
    var hiddenUnlocked = false
    var operationsUnlocked = false
    private val unlockedFolders = HashSet<String>()
    private var backgroundSince = 0L

    fun isFolderUnlocked(albumKey: String) = albumKey in unlockedFolders
    fun unlockFolder(albumKey: String) { unlockedFolders += albumKey }

    fun onBackground(nowMs: Long) { backgroundSince = nowMs }

    fun onForeground(nowMs: Long) {
        if (backgroundSince > 0L && SecurityRules.expired(backgroundSince, nowMs, GRACE_MS)) lockAll()
        backgroundSince = 0L
    }

    fun lockAll() {
        appUnlocked = false
        hiddenUnlocked = false
        operationsUnlocked = false
        unlockedFolders.clear()
    }
}

internal object SecurityRules {
    fun expired(backgroundSinceMs: Long, nowMs: Long, graceMs: Long): Boolean = nowMs - backgroundSinceMs > graceMs

    /** Media inside a secure folder (or its subfolders) never shows in Recents, Favorites or searches. */
    fun insideSecureFolder(albumKey: String?, secureKeys: Set<String>): Boolean {
        if (albumKey.isNullOrBlank()) return false
        return secureKeys.any { key -> albumKey == key || albumKey.startsWith(key.trimEnd('/') + "/") }
    }
}

/** Answers to the secret question match regardless of case, accents and extra spaces. */
internal object RecoveryRules {
    const val MIN_ANSWER = 2
    const val PRESET_PREFIX = "preset:"

    fun normalize(answer: String): String = java.text.Normalizer.normalize(answer.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\s+"), " ")
        .lowercase(java.util.Locale.ROOT)

    fun isAcceptable(answer: String): Boolean = normalize(answer).length >= MIN_ANSWER

    fun presetIndex(question: String?): Int? = question?.takeIf { it.startsWith(PRESET_PREFIX) }
        ?.removePrefix(PRESET_PREFIX)?.toIntOrNull()
}
