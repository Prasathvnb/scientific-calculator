package com.sci.calc.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.sci.calc.security.CryptoManager
import com.sci.calc.vault.Storage
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import kotlin.math.min

/**
 * Lets ExoPlayer read an encrypted locked-folder file directly, decrypting on
 * the fly. Seeking works because AES-CTR can start at any byte position.
 */
@OptIn(UnstableApi::class)
class EncryptedDataSource(private val ctx: Context) : BaseDataSource(false) {

    private var pfd: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var cipher: Cipher? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false
    private var buffer = ByteArray(64 * 1024)

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val location = if (dataSpec.uri.scheme == "file") dataSpec.uri.path!! else dataSpec.uri.toString()
        val p = Storage.openPfd(ctx, location) ?: throw IOException("Can't open file")
        pfd = p
        val stream = ParcelFileDescriptor.AutoCloseInputStream(p)
        input = stream
        val channel = stream.channel
        val header = ByteArray(CryptoManager.HEADER_SIZE)
        channel.position(0)
        channel.read(ByteBuffer.wrap(header))
        if (!CryptoManager.isEncryptedHeader(header)) throw IOException("Not an encrypted file")
        val iv = header.copyOfRange(4, 20)
        val plainLength = p.statSize - CryptoManager.HEADER_SIZE
        if (dataSpec.position > plainLength) throw IOException("Position out of range")
        channel.position(CryptoManager.HEADER_SIZE + dataSpec.position)
        cipher = CryptoManager.decryptCipherAt(ctx, iv, dataSpec.position)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else plainLength - dataSpec.position
        transferStarted(dataSpec)
        opened = true
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val want = min(min(length.toLong(), remaining), buffer.size.toLong()).toInt()
        val n = input!!.read(buffer, 0, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        val out = cipher!!.update(buffer, 0, n, target, offset)
        remaining -= n
        bytesTransferred(n)
        return out
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        input = null; pfd = null; cipher = null
        if (opened) { opened = false; transferEnded() }
        uri = null
    }
}
