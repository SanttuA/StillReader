package io.github.santtua.stillreader

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

internal data class BackupBook(
    val key: String,
    val title: String,
    val pageCount: Int,
    val lastOpened: Long,
    val position: ReadingPosition,
    val comfort: ReadingComfort
)

internal object LibraryBackup {
    const val MIME = "application/json"
    private const val FORMAT = "StillReader"
    private const val VERSION = 1
    private const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_BOOKS = 5_000
    private val keyPattern = Regex("[a-f0-9]{32}")

    fun encode(books: List<BackupBook>): ByteArray {
        require(books.size <= MAX_BOOKS)
        val bytes = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(bytes, Charsets.UTF_8)).use { json ->
            json.setIndent("  ")
            json.beginObject()
            json.name("format").value(FORMAT)
            json.name("version").value(VERSION.toLong())
            json.name("books").beginArray()
            for (book in books) {
                json.beginObject()
                json.name("id").value(book.key)
                json.name("title").value(book.title)
                json.name("pageCount").value(book.pageCount.toLong())
                json.name("lastOpened").value(book.lastOpened)
                val position = book.position
                json.name("position").beginArray()
                    .value(position.page.toLong()).value(position.anchor.toLong()).value(position.offset)
                    .value(position.zoom).value(position.pan).endArray()
                val comfort = book.comfort
                json.name("comfort").beginArray()
                    .value(comfort.theme.name).value(comfort.recolor).value(comfort.fitPage)
                    .value(comfort.keepAwake).value(comfort.crop.toLong()).endArray()
                json.endObject()
            }
            json.endArray()
            json.endObject()
        }
        if (bytes.size() > MAX_BYTES) throw IOException("Backup limit exceeded.")
        return bytes.toByteArray()
    }

    fun decode(input: InputStream): List<BackupBook> {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES)
        return JsonReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder())).use { json ->
            var books: List<BackupBook>? = null
            json.fields(3) { name ->
                when (name) {
                    "format" -> require(json.string() == FORMAT)
                    "version" -> require(json.integer() == VERSION.toLong())
                    "books" -> books = json.books()
                    else -> throw IllegalArgumentException("Unknown backup field.")
                }
            }
            require(json.peek() == JsonToken.END_DOCUMENT)
            requireNotNull(books)
        }
    }

    private fun JsonReader.books(): List<BackupBook> {
        val seen = mutableSetOf<String>()
        beginArray()
        val books = buildList {
            while (hasNext()) {
                require(size < MAX_BOOKS)
                val book = readBook()
                require(seen.add(book.key))
                add(book)
            }
        }
        endArray()
        return books
    }

    private fun JsonReader.readBook(): BackupBook {
        var key = ""
        var title = ""
        var count = 0
        var lastOpened = 0L
        var position = ReadingPosition()
        var comfort = ReadingComfort()
        fields(6) { name ->
            when (name) {
                "id" -> key = string().also { require(keyPattern.matches(it)) }
                "title" -> title = requireNotNull(cleanBookTitle(string()))
                "pageCount" -> count = int(1..PdfRenderProtocol.MAX_PAGES)
                "lastOpened" -> lastOpened = integer().also { require(it >= 0) }
                "position" -> position = readPosition()
                "comfort" -> comfort = readComfort()
                else -> throw IllegalArgumentException("Unknown book field.")
            }
        }
        require(position.page < count && position.anchor < count)
        return BackupBook(key, title, count, lastOpened, position, comfort)
    }

    private fun JsonReader.readPosition(): ReadingPosition {
        beginArray()
        val position = ReadingPosition(
            int(0 until PdfRenderProtocol.MAX_PAGES), int(0 until PdfRenderProtocol.MAX_PAGES),
            decimal(0f..0.99f), decimal(1f..4f), decimal(0f..1f)
        )
        endArray()
        return position
    }

    private fun JsonReader.readComfort(): ReadingComfort {
        beginArray()
        val name = string()
        val theme = requireNotNull(ReadingTheme.entries.firstOrNull { it.name == name })
        val comfort = ReadingComfort(theme, nextBoolean(), nextBoolean(), nextBoolean(), int(0..10))
        require(comfort.crop == 0 || comfort.crop == 5 || comfort.crop == 10)
        endArray()
        return comfort
    }

    private fun JsonReader.fields(count: Int, read: (String) -> Unit) {
        val seen = mutableSetOf<String>()
        beginObject()
        while (hasNext()) {
            val name = nextName()
            require(seen.add(name))
            read(name)
        }
        endObject()
        require(seen.size == count)
    }

    private fun JsonReader.string(): String {
        require(peek() == JsonToken.STRING)
        return nextString()
    }

    private fun JsonReader.integer(): Long {
        require(peek() == JsonToken.NUMBER)
        return requireNotNull(nextString().toLongOrNull())
    }

    private fun JsonReader.int(range: IntRange): Int {
        val value = integer()
        require(value >= range.first && value <= range.last)
        return value.toInt()
    }

    private fun JsonReader.decimal(range: ClosedFloatingPointRange<Float>): Float {
        require(peek() == JsonToken.NUMBER)
        val value = nextDouble().toFloat()
        require(value.isFinite() && value in range)
        return value
    }
}
