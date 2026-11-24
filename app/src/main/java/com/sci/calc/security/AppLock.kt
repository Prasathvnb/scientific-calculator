package com.sci.calc.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Holds the secret code (stored only as a salted hash), the numeric backup code
 * and the in-memory locked / unlocked state.
 */
object AppLock {
    private lateinit var prefs: SharedPreferences

    @Volatile
    var unlocked = false
        private set

    /** Set before opening a system screen (file picker, share sheet, camera) so it doesn't re-lock. */
    @Volatile
    var allowOneExternalTrip = false

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("lock", Context.MODE_PRIVATE)
    }

    val isSetUp: Boolean get() = prefs.contains("code_hash")

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("bio", false)
        set(v) = prefs.edit().putBoolean("bio", v).apply()

    fun setCode(code: String) {
        val salt = randomBytes(16)
        prefs.edit()
            .putString("code_salt", salt.toHex())
            .putString("code_hash", hash(code, salt))
            .apply()
    }

    fun checkCode(code: String): Boolean {
        val salt = prefs.getString("code_salt", null)?.hexToBytes() ?: return false
        return MessageDigest.isEqual(
            hash(code, salt).toByteArray(),
            (prefs.getString("code_hash", "") ?: "").toByteArray()
        )
    }

    /** Creates a new 16-digit backup code, stores its hash, returns it to show once. */
    fun newBackupCode(): String {
        val rnd = SecureRandom()
        val code = (1..16).map { rnd.nextInt(10) }.joinToString("")
        val salt = randomBytes(16)
        prefs.edit()
            .putString("backup_salt", salt.toHex())
            .putString("backup_hash", hash(code, salt))
            .apply()
        return code
    }

    fun checkBackupCode(code: String): Boolean {
        val salt = prefs.getString("backup_salt", null)?.hexToBytes() ?: return false
        return hash(code, salt) == prefs.getString("backup_hash", "")
    }

    fun unlock() {
        unlocked = true
    }

    fun lock() {
        unlocked = false
    }

    fun onAppBackgrounded() {
        if (allowOneExternalTrip) {
            allowOneExternalTrip = false
            return
        }
        lock()
    }

    private fun hash(code: String, salt: ByteArray): String {
        var d = salt + code.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(20000) { d = md.digest(d) }
        return d.toHex()
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
