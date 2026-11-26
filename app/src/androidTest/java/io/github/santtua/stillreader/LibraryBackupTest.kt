package io.github.santtua.stillreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryBackupTest {
    private val fixture = BackupBook(
        UUID.randomUUID().toString().replace("-", ""), "Portable fixture.pdf", 12, 3000,
        ReadingPosition(5, 4, 0.45f, 3f, 0.75f),
        ReadingComfort(ReadingTheme.HIGH_CONTRAST, recolor = true, keepAwake = true, crop = 10)
    )

    @Test
    fun preservesMetadataAndRestoresWithoutFileAccess() {
        val encoded = LibraryBackup.encode(listOf(fixture))
        val decoded = LibraryBackup.decode(encoded.inputStream())
        assertEquals(listOf(fixture), decoded)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = BookLibrary.get(context)
        repeat(2) { assertTrue(receive { library.restore(decoded, it) }) }
        val books = receive<List<Book>?> { library.list(it) }.orEmpty()
        val restored = books.single { it.backupKey == fixture.key }
        try {
            assertEquals(fixture.position, restored.position)
            assertEquals(fixture.comfort, restored.comfort)
            assertTrue(restored.needsFile)
            assertNull(restored.copyName)
            assertTrue(context.contentResolver.persistedUriPermissions.none { it.uri == restored.uri })
        } finally {
            assertTrue(receive { library.remove(restored, it) })
        }
    }

    @Test
    fun rejectsInvalidStructureValuesAndOversizedInput() {
        val valid = LibraryBackup.encode(listOf(fixture)).toString(Charsets.UTF_8)
        val invalid = listOf(
            valid.replace("\"version\": 1", "\"version\": 2"),
            valid.replace("\"version\": 1", "\"version\": 1, \"version\": 1"),
            valid.replace("\"version\": 1", "\"version\": \"1\""),
            valid.replace("\"pageCount\": 12", "\"pageCount\": 4"),
            valid.replace("\"pageCount\": 12", "\"pageCount\": []"),
            valid.replace("3.0", "1e300"),
            valid.replace(fixture.key, "content://example.invalid/file"),
            valid + " {}",
            valid.dropLast(1),
            LibraryBackup.encode(listOf(fixture, fixture)).toString(Charsets.UTF_8)
        )
        for ((index, candidate) in invalid.withIndex()) {
            assertTrue("Invalid backup case ${index + 1} was accepted", runCatching {
                LibraryBackup.decode(candidate.byteInputStream())
            }.isFailure)
        }
        assertTrue(runCatching { LibraryBackup.decode(ByteArrayInputStream(ByteArray(4 * 1024 * 1024 + 1))) }.isFailure)
    }

    private fun <T> receive(action: ((T) -> Unit) -> Unit): T {
        val result = ArrayBlockingQueue<Result<T>>(1)
        action { result.offer(Result.success(it)) }
        return checkNotNull(result.poll(10, TimeUnit.SECONDS)).getOrThrow()
    }
}
