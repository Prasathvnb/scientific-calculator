package com.sci.calc.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(val id: Long, val title: String, val url: String, val time: Long)

data class VaultFile(
    val id: Long,
    val name: String,
    val mime: String,
    val location: String,   // absolute file path, or a content:// URI on the memory card
    val size: Long,
    val encrypted: Boolean,
    val time: Long,
)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "app.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, url TEXT, time INTEGER)")
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, url TEXT, time INTEGER)")
        db.execSQL("CREATE INDEX history_time ON history(time)")
        db.execSQL(
            "CREATE TABLE files(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, mime TEXT, location TEXT, " +
                "size INTEGER, encrypted INTEGER, time INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- bookmarks
    fun addBookmark(title: String, url: String) {
        if (isBookmarked(url)) return
        writableDatabase.insert("bookmarks", null, ContentValues().apply {
            put("title", title); put("url", url); put("time", System.currentTimeMillis())
        })
    }

    fun removeBookmark(url: String) = writableDatabase.delete("bookmarks", "url=?", arrayOf(url))
    fun deleteBookmark(id: Long) = writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString()))

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }

    fun bookmarks(): List<Entry> = entries("bookmarks", null)

    // ---- history
    fun addHistory(title: String, url: String) {
        val db = writableDatabase
        db.delete("history", "url=?", arrayOf(url))
        db.insert("history", null, ContentValues().apply {
            put("title", title); put("url", url); put("time", System.currentTimeMillis())
        })
    }

    fun history(filter: String? = null): List<Entry> = entries("history", filter)
    fun deleteHistory(id: Long) = writableDatabase.delete("history", "id=?", arrayOf(id.toString()))
    fun clearHistory() = writableDatabase.delete("history", null, null)

    fun suggestions(prefix: String): List<Entry> {
        if (prefix.isBlank()) return emptyList()
        val like = "%$prefix%"
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT id,title,url,time FROM bookmarks WHERE url LIKE ? OR title LIKE ? " +
                "UNION SELECT id,title,url,time FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY time DESC LIMIT 6",
            arrayOf(like, like, like, like)
        ).use { c -> while (c.moveToNext()) out.add(Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3))) }
        return out
    }

    private fun entries(table: String, filter: String?): List<Entry> {
        val out = ArrayList<Entry>()
        val (where, args) = if (filter.isNullOrBlank()) "" to null
        else " WHERE title LIKE ? OR url LIKE ?" to arrayOf("%$filter%", "%$filter%")
        readableDatabase.rawQuery("SELECT id,title,url,time FROM $table$where ORDER BY time DESC LIMIT 1000", args).use { c ->
            while (c.moveToNext()) out.add(Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
        }
        return out
    }

    // ---- downloaded files
    fun addFile(name: String, mime: String, location: String, size: Long, encrypted: Boolean): Long =
        writableDatabase.insert("files", null, ContentValues().apply {
            put("name", name); put("mime", mime); put("location", location)
            put("size", size); put("encrypted", if (encrypted) 1 else 0); put("time", System.currentTimeMillis())
        })

    fun files(): List<VaultFile> {
        val out = ArrayList<VaultFile>()
        readableDatabase.rawQuery("SELECT id,name,mime,location,size,encrypted,time FROM files ORDER BY time DESC", null).use { c ->
            while (c.moveToNext()) out.add(
                VaultFile(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5) == 1, c.getLong(6))
            )
        }
        return out
    }

    fun file(id: Long): VaultFile? = files().firstOrNull { it.id == id }
    fun renameFile(id: Long, name: String) =
        writableDatabase.update("files", ContentValues().apply { put("name", name) }, "id=?", arrayOf(id.toString()))
    fun deleteFile(id: Long) = writableDatabase.delete("files", "id=?", arrayOf(id.toString()))

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }
}
