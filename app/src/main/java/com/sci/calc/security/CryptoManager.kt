package com.sci.calc.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * File encryption for the locked folder.
 *
 * A random 256-bit data key encrypts the files with AES-CTR (seekable, so videos
 * can be played and scrubbed without decrypting to disk). The data key itself is
 * wrapped by a hardware-backed Android Keystore key, so the files cannot be
 * opened on another phone or by another app.
 *
 * File layout: "SCV1" (4 bytes) + IV (16 bytes) + ciphertext.
 */
object CryptoManager {
    const val HEADER_SIZE = 20
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val WRAP_ALIAS = "scv_wrap_key"

    @Volatile
    private var dataKey: SecretKey? = null

    @Synchronized
    fun key(ctx: Context): SecretKey {
        dataKey?.let { return it }
        val prefs = ctx.getSharedPreferences("crypto", Context.MODE_PRIVATE)
        val wrapped = prefs.getString("dk", null)
        val raw: ByteArray = if (wrapped != null) {
            val blob = Base64.decode(wrapped, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, iv))
            c.doFinal(blob, 12, blob.size - 12)
        } else {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, wrapKey())
            val blob = c.iv + c.doFinal(k)
            prefs.edit().putString("dk", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            k
        }
        return SecretKeySpec(raw, "AES").also { dataKey = it }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Writes the header and returns a stream that encrypts everything written to it. */
    fun encrypt(ctx: Context, out: OutputStream): OutputStream {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        out.write(MAGIC)
        out.write(iv)
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(ctx), IvParameterSpec(iv))
        return CipherOutputStream(out, c)
    }

    fun isEncryptedHeader(header: ByteArray): Boolean =
        header.size >= HEADER_SIZE && header.copyOfRange(0, 4).contentEquals(MAGIC)

    /** Cipher positioned so the next byte it processes is plaintext byte [position]. */
    fun decryptCipherAt(ctx: Context, iv: ByteArray, position: Long): Cipher {
        val block = position / 16
        val counter = BigInteger(1, iv).add(BigInteger.valueOf(block))
        var bytes = counter.toByteArray()
        bytes = when {
            bytes.size > 16 -> bytes.copyOfRange(bytes.size - 16, bytes.size)
            bytes.size < 16 -> ByteArray(16 - bytes.size) + bytes
            else -> bytes
        }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(ctx), IvParameterSpec(bytes))
        val skip = (position % 16).toInt()
        if (skip > 0) c.update(ByteArray(skip))
        return c
    }
}
