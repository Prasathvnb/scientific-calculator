package com.sci.calc.vault

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.sci.calc.data.Prefs
import com.sci.calc.data.VaultFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Where downloads go.
 *
 * Default: the app's own folder on the memory card (Android/data/com.sci.calc/files/data),
 * which other apps and the gallery can't index. The user can instead pick any folder on
 * the memory card in Settings (stored as a Storage Access Framework tree URI).
 */
object Storage {

    data class Target(val out: OutputStream, val location: String)

    /** The app's private folder, on the removable memory card if one is present. */
    fun defaultDir(ctx: Context): File {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        val sd = dirs.firstOrNull { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
        val base = sd ?: dirs.firstOrNull() ?: ctx.filesDir
        return File(base, "data").apply { mkdirs(); File(this, ".nomedia").createNewFile() }
    }

    fun hasMemoryCard(ctx: Context): Boolean =
        ctx.getExternalFilesDirs(null).filterNotNull().any {
            runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false)
        }

    fun describeTarget(ctx: Context): String {
        val tree = Prefs(ctx).downloadTree
        if (tree != null) {
            val doc = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
            if (doc != null && doc.canWrite()) return "Chosen folder: " + (Uri.parse(tree).lastPathSegment ?: tree)
        }
        val d = defaultDir(ctx)
        return (if (hasMemoryCard(ctx)) "Memory card (private app folder): " else "Phone storage (private app folder): ") + d.absolutePath
    }

    /** Creates the output file. Encrypted files get a random, meaningless name. */
    fun create(ctx: Context, displayName: String, mime: String, encrypted: Boolean): Target {
        val diskName = if (encrypted) UUID.randomUUID().toString().replace("-", "").take(20) + ".dat" else safeName(displayName)
        val diskMime = if (encrypted) "application/octet-stream" else mime
        val tree = Prefs(ctx).downloadTree
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
            if (dir != null && dir.canWrite()) {
                if (dir.findFile(".nomedia") == null) runCatching { dir.createFile("application/octet-stream", ".nomedia") }
                val f = dir.createFile(diskMime, diskName)
                if (f != null) {
                    val os = ctx.contentResolver.openOutputStream(f.uri, "w") ?: error("Can't write to folder")
                    return Target(os, f.uri.toString())
                }
            }
        }
        val dir = defaultDir(ctx)
        var f = File(dir, diskName)
        var n = 1
        while (f.exists()) f = File(dir, "${f.nameWithoutExtension}($n).${f.extension}").also { n++ }
        return Target(FileOutputStream(f), f.absolutePath)
    }

    fun openPfd(ctx: Context, location: String): ParcelFileDescriptor? =
        if (location.startsWith("/")) ParcelFileDescriptor.open(File(location), ParcelFileDescriptor.MODE_READ_ONLY)
        else ctx.contentResolver.openFileDescriptor(Uri.parse(location), "r")

    fun uriFor(location: String): Uri = if (location.startsWith("/")) Uri.fromFile(File(location)) else Uri.parse(location)

    fun delete(ctx: Context, f: VaultFile): Boolean =
        if (f.location.startsWith("/")) File(f.location).delete()
        else DocumentFile.fromSingleUri(ctx, Uri.parse(f.location))?.delete() ?: false

    fun safeName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(120).ifBlank { "download" }
}
