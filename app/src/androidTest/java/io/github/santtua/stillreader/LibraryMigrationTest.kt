package io.github.santtua.stillreader

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    @Test
    fun keepsVersionOneBooksAndReadingPositions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verify_v1_library") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = context.getDatabasePath("library.db")
        assertFalse("The migration test requires a fresh application install", path.exists())
        val directory = checkNotNull(path.parentFile)
        assertTrue(directory.isDirectory || directory.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL(
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
                    cover BLOB
                )
                """.trimIndent()
            )
            database.insertOrThrow("books", null, ContentValues().apply {
                put("_id", 49)
                put("uri", "content://example.invalid/synthetic-book")
                put("title", "Legacy fixture")
                put("page_count", 8)
                put("page", 2)
                put("anchor", 2)
                put("scroll_offset", 0.25f)
                put("zoom", 1.5f)
                put("pan", 0.75f)
                put("last_opened", 3000L)
            })
            database.version = 1
        }
        val results = ArrayBlockingQueue<List<Book>>(1)
        val library = BookLibrary.get(context)
        library.list { results.offer(it.orEmpty()) }
        val book = checkNotNull(results.poll(10, TimeUnit.SECONDS)).single()
        assertEquals(49L, book.id)
        assertEquals("Legacy fixture", book.title)
        assertEquals(ReadingPosition(2, 2, 0.25f, 1.5f, 0.75f), book.position)
        assertEquals(ReadingComfort(), book.comfort)
        val removed = ArrayBlockingQueue<Boolean>(1)
        library.remove(book) { removed.offer(it) }
        assertTrue(removed.poll(5, TimeUnit.SECONDS) == true)
    }
}
