package io.github.santtua.stillreader

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal data class Book(
    val id: Long,
    val uri: Uri,
    val title: String,
    val copyName: String?,
    val pageCount: Int,
    val position: ReadingPosition,
    val lastOpened: Long,
    val needsFile: Boolean,
    val hasCover: Boolean,
    val comfort: ReadingComfort = ReadingComfort(),
    val backupKey: String
)

internal class BookLibrary private constructor(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "book-library") }
    private val copies = File(app.noBackupFilesDir, "books")
    private val database = object : SQLiteOpenHelper(app, "library.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE books (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uri TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    copy_name TEXT,
                    page_count INTEGER NOT NULL,
                    page INTEGER NOT NULL DEFAULT 0,
                    anchor INTEGER NOT NULL DEFAULT 0,
                    scroll_offset REAL NOT NULL DEFAULT 0,
                    zoom REAL NOT NULL DEFAULT 1,
                    pan REAL NOT NULL DEFAULT 0.5,
                    last_opened INTEGER NOT NULL,
                    needs_file INTEGER NOT NULL DEFAULT 0,
                    cover BLOB,
                    theme TEXT NOT NULL DEFAULT 'SYSTEM',
                    recolor INTEGER NOT NULL DEFAULT 0,
                    fit_page INTEGER NOT NULL DEFAULT 0,
                    awake INTEGER NOT NULL DEFAULT 0,
                    crop INTEGER NOT NULL DEFAULT 0,
                    backup_key TEXT NOT NULL
                )
                """.trimIndent()
            )
            createBackupKeyIndex(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion !in 1..2 || newVersion != 3) throw SQLiteException("Unsupported library database version.")
            if (oldVersion == 1) {
                db.execSQL("ALTER TABLE books ADD COLUMN theme TEXT NOT NULL DEFAULT 'SYSTEM'")
                db.execSQL("ALTER TABLE books ADD COLUMN recolor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN fit_page INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN awake INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN crop INTEGER NOT NULL DEFAULT 0")
            }
            db.execSQL("ALTER TABLE books ADD COLUMN backup_key TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE books SET backup_key = lower(hex(randomblob(16)))")
            createBackupKeyIndex(db)
        }

        private fun createBackupKeyIndex(db: SQLiteDatabase) {
            db.execSQL("CREATE UNIQUE INDEX books_backup_key ON books(backup_key)")
        }
    }

    init {
        submit(Unit, {
            val used = mutableSetOf<String>()
            database.readableDatabase.query(
                "books", arrayOf("copy_name"), null, null, null, null, null
            ).use { rows ->
                while (rows.moveToNext()) rows.getString(0)?.let(used::add)
            }
            val expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            copies.listFiles()?.forEach { file ->
                if ((COPY_NAME.matches(file.name) && file.name !in used) ||
                    (file.name.startsWith("draft-") && file.lastModified() < expiry)
                ) file.delete()
            }
            resolver.persistedUriPermissions.filter { it.isReadPermission }.forEach { grant ->
                if (find("uri", grant.uri.toString()) == null) release(grant.uri)
            }
        })
    }

    fun list(callback: (List<Book>?) -> Unit) = submit(null, { query() }, callback)

    fun find(uri: Uri, callback: (Book?) -> Unit) =
        submit(null, { find("uri", uri.toString()) }, callback)

    fun copyFor(book: Book): File? = book.copyName?.takeIf(COPY_NAME::matches)?.let { File(copies, it) }

    fun remember(
        uri: Uri,
        displayName: String?,
        pageCount: Int,
        retainGrant: Boolean,
        draft: File?,
        replaceId: Long?,
        start: ReadingPosition?,
        defaults: ReadingComfort,
        callback: (Book?) -> Unit
    ) = submit(null, { register(uri, displayName, pageCount, retainGrant, draft, replaceId, start, defaults) }, callback)

    private fun register(
        uri: Uri,
        displayName: String?,
        pageCount: Int,
        retainGrant: Boolean,
        draft: File?,
        replaceId: Long?,
        start: ReadingPosition?,
        defaults: ReadingComfort
    ): Book? {
        var installedCopy: File? = null
        var acquiredGrant = false
        var registered = false
        try {
            val match = find("uri", uri.toString())
            if (replaceId != null && match != null && match.id != replaceId) return match
            val old = match ?: replaceId?.let { find("_id", it.toString()) }
            val keptCopy = old?.copyName?.takeIf { replaceId == null && old.uri == uri }
            if (draft != null) {
                val destination = File(copies, "${UUID.randomUUID()}.pdf")
                if (!draft.renameTo(destination)) return null
                installedCopy = destination
            }
            val copyName = installedCopy?.name ?: keptCopy
            val hadGrant = copyName == null && resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (copyName == null && !hadGrant && retainGrant) {
                acquiredGrant = try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                } catch (_: SecurityException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            if (copyName == null && !hadGrant && !acquiredGrant) return null

            val title = displayName ?: old?.title ?: app.getString(R.string.untitled_pdf)
            val values = positionValues(
                (start ?: old?.position ?: ReadingPosition()).bounded(pageCount)
            ).apply {
                put("uri", uri.toString())
                put("title", title)
                put("copy_name", copyName)
                put("page_count", pageCount)
                put("last_opened", System.currentTimeMillis())
                put("needs_file", 0)
                if (replaceId != null || old?.pageCount != pageCount) putNull("cover")
            }
            val db = database.writableDatabase
            val id = if (old == null) {
                values.put("backup_key", UUID.randomUUID().toString().replace("-", ""))
                values.putAll(comfortValues(defaults))
                db.insertOrThrow("books", null, values)
            } else {
                db.update("books", values, "_id = ?", arrayOf(old.id.toString()))
                old.id
            }
            registered = true
            if (old?.copyName != copyName) old?.let { copyFor(it)?.delete() }
            if (old != null && old.uri != uri) release(old.uri)
            if (copyName != null) release(uri)
            return find("_id", id.toString())
        } finally {
            draft?.delete()
            if (!registered) {
                installedCopy?.delete()
                if (acquiredGrant) release(uri)
            }
        }
    }

    fun markMissing(id: Long) = submit(Unit, {
        database.writableDatabase.update("books", ContentValues().apply { put("needs_file", 1) },
            "_id = ?", arrayOf(id.toString()))
        Unit
    })

    fun savePosition(id: Long, position: ReadingPosition) = submit(Unit, {
        database.writableDatabase.update("books", positionValues(position), "_id = ?", arrayOf(id.toString()))
        Unit
    })

    fun saveComfort(id: Long, comfort: ReadingComfort) = submit(Unit, {
        database.writableDatabase.update("books", comfortValues(comfort), "_id = ?", arrayOf(id.toString()))
        Unit
    })

    fun backup(destination: Uri, callback: (List<BackupBook>?) -> Unit) = submit(null, {
        // The save picker creates a new file. Also protect any existing library source by URI.
        if (find("uri", destination.toString()) != null) return@submit null
        query(limit = (LibraryBackup.MAX_BOOKS + 1).toString())
            .takeIf { it.size <= LibraryBackup.MAX_BOOKS }
            ?.map { BackupBook(it.backupKey, it.title, it.pageCount, it.lastOpened, it.position, it.comfort) }
    }, callback)

    fun restore(books: List<BackupBook>, callback: (Boolean) -> Unit) = submit(false, {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            for (book in books) {
                val existing = find("backup_key", book.key)
                val count = existing?.pageCount ?: book.pageCount
                val values = positionValues(book.position.bounded(count)).apply {
                    putAll(comfortValues(book.comfort))
                    put("last_opened", book.lastOpened)
                }
                if (existing == null) {
                    values.put("uri", "stillreader-backup:${book.key}")
                    values.put("backup_key", book.key)
                    values.put("title", book.title)
                    values.put("page_count", count)
                    values.put("needs_file", 1)
                    db.insertOrThrow("books", null, values)
                } else {
                    db.update("books", values, "_id = ?", arrayOf(existing.id.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        true
    }, callback)

    fun clearCovers(callback: (Boolean) -> Unit) = submit(false, {
        database.writableDatabase.update("books", ContentValues().apply { putNull("cover") }, null, null)
        true
    }, callback)

    fun clearReadingData(callback: (Boolean) -> Unit) = submit(false, {
        database.writableDatabase.delete("books", null, null)
        resolver.persistedUriPermissions.filter { it.isReadPermission }.forEach { release(it.uri) }
        var deleted = true
        copies.listFiles()?.forEach { file ->
            if ((COPY_NAME.matches(file.name) || file.name.startsWith("draft-")) && !file.delete() && file.exists()) {
                deleted = false
            }
        }
        deleted
    }, callback)

    fun remove(book: Book, callback: (Boolean) -> Unit) = submit(false, {
        val current = find("_id", book.id.toString()) ?: return@submit false
        if (current.copyName != null) {
            val copy = copyFor(current) ?: return@submit false
            try {
                Files.deleteIfExists(copy.toPath())
            } catch (_: IOException) {
                return@submit false
            }
        }
        if (database.writableDatabase.delete("books", "_id = ?", arrayOf(book.id.toString())) != 1) {
            return@submit false
        }
        release(current.uri)
        true
    }, callback)

    fun saveCover(id: Long, source: Bitmap) = submit(Unit, {
        val scale = minOf(1f, 144f / source.width, 208f / source.height)
        val thumbnail = Bitmap.createScaledBitmap(
            source, (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1), true
        )
        try {
            val output = ByteArrayOutputStream()
            if (thumbnail.compress(Bitmap.CompressFormat.JPEG, 80, output) && output.size() <= 64 * 1024) {
                val values = ContentValues().apply { put("cover", output.toByteArray()) }
                database.writableDatabase.update("books", values, "_id = ? AND cover IS NULL", arrayOf(id.toString()))
            }
        } finally {
            if (thumbnail !== source) thumbnail.recycle()
        }
    })

    fun loadCover(id: Long, callback: (Bitmap?) -> Unit) = submit(null, {
        database.readableDatabase.query("books", arrayOf("cover"), "_id = ?", arrayOf(id.toString()),
            null, null, null).use { row ->
            val bytes = if (row.moveToFirst()) row.getBlob(0) else null
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }, callback)

    private fun release(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The provider may already have revoked this grant.
        } catch (_: IllegalArgumentException) {
            // The URI may no longer identify a document.
        }
    }

    private fun find(column: String, value: String): Book? = query("$column = ?", arrayOf(value)).firstOrNull()

    private fun query(selection: String? = null, args: Array<String>? = null, limit: String? = null): List<Book> =
        database.readableDatabase.query("books", COLUMNS, selection, args, null, null, "last_opened DESC, _id DESC", limit).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.book()) }
        }

    private fun Cursor.book(): Book {
        val count = getInt(4)
        return Book(
            getLong(0), Uri.parse(getString(1)), getString(2), getString(3), count,
            ReadingPosition(getInt(5), getInt(6), getFloat(7), getFloat(8), getFloat(9)).bounded(count),
            getLong(10), getInt(11) != 0, getInt(12) != 0,
            ReadingComfort(ReadingTheme.stored(getString(13)), getInt(14) != 0, getInt(15) != 0,
                getInt(16) != 0, getInt(17)).bounded(),
            getString(18)
        )
    }

    private fun positionValues(position: ReadingPosition) = ContentValues().apply {
        put("page", position.page)
        put("anchor", position.anchor)
        put("scroll_offset", position.offset)
        put("zoom", position.zoom)
        put("pan", position.pan)
    }

    private fun comfortValues(comfort: ReadingComfort) = ContentValues().apply {
        put("theme", comfort.theme.name)
        put("recolor", comfort.recolor)
        put("fit_page", comfort.fitPage)
        put("awake", comfort.keepAwake)
        put("crop", comfort.crop)
    }

    private fun <T> submit(fallback: T, operation: () -> T, callback: ((T) -> Unit)? = null) {
        worker.execute {
            val result = try {
                operation()
            } catch (_: SQLiteException) {
                fallback
            } catch (_: SecurityException) {
                fallback
            }
            if (callback != null) app.mainExecutor.execute { callback(result) }
        }
    }

    companion object {
        private val COLUMNS = arrayOf("_id", "uri", "title", "copy_name", "page_count", "page", "anchor",
            "scroll_offset", "zoom", "pan", "last_opened", "needs_file", "cover IS NOT NULL",
            "theme", "recolor", "fit_page", "awake", "crop", "backup_key")
        private val COPY_NAME = Regex("[a-f0-9-]{36}\\.pdf")

        // The singleton holds the application context, including when created by an Activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: BookLibrary? = null
        fun get(context: Context): BookLibrary = instance ?: synchronized(this) {
            instance ?: BookLibrary(context).also { instance = it }
        }
    }
}
