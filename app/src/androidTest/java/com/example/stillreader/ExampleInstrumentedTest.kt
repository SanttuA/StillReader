package io.github.santtua.stillreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedPdfProbeTest {
    @Test
    fun rendersAndSearchesReadOnlyDocumentsInAnIsolatedProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File.createTempFile("stillreader-probe", ".pdf", context.cacheDir)
        val invalid = File.createTempFile("stillreader-invalid", ".pdf", context.cacheDir)
        val sentinel = File(context.filesDir, PdfProbe.PRIVATE_FILE)
        val repliesThread = HandlerThread("pdf-probe-test").apply { start() }
        val service = ArrayBlockingQueue<IBinder>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service.offer(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false

        try {
            writeFixture(pdf)
            invalid.writeText("This is a synthetic invalid PDF.")
            sentinel.writeText("private")
            assertEquals("private", sentinel.readText())

            bound = context.bindService(
                Intent(context, PdfProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            assertTrue("Could not bind the private debug service", bound)
            val remote = Messenger(checkNotNull(service.poll(15, TimeUnit.SECONDS)) {
                "Timed out waiting for the PDF probe service"
            })
            val results = ArrayBlockingQueue<Bundle>(1)
            val replies = Messenger(Handler(repliesThread.looper) { message ->
                if (message.what == PdfProbe.RESULT) results.offer(message.data)
                true
            })

            fun inspect(file: File, page: Int): Bundle {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    remote.send(Message.obtain(null, PdfProbe.REQUEST).apply {
                        replyTo = replies
                        data = Bundle().apply {
                            putParcelable(PdfProbe.DESCRIPTOR, fd)
                            putInt(PdfProbe.PAGE, page)
                        }
                    })
                }
                return checkNotNull(results.poll(15, TimeUnit.SECONDS)) {
                    "Timed out waiting for PDF inspection"
                }
            }

            val text = inspect(pdf, 0)
            assertTrue(text.getBoolean(PdfProbe.OPENED))
            assertEquals(2, text.getInt(PdfProbe.PAGE_COUNT))
            assertTrue(text.getBoolean(PdfProbe.RENDERED))
            assertTrue(text.getBoolean(PdfProbe.HAS_TEXT))
            assertEquals(1, text.getInt(PdfProbe.MATCH_COUNT))
            assertTrue(text.getBoolean(PdfProbe.HAS_MATCH_BOUNDS))
            val workerUid = text.getInt(PdfProbe.WORKER_UID, -1)
            assertTrue(workerUid > 0)
            assertNotEquals(Process.myUid(), workerUid)
            assertTrue(text.getBoolean(PdfProbe.PRIVATE_FILE_DENIED))

            val image = inspect(pdf, 1)
            assertTrue(image.getBoolean(PdfProbe.OPENED))
            assertTrue(image.getBoolean(PdfProbe.RENDERED))
            assertFalse(image.getBoolean(PdfProbe.HAS_TEXT))
            assertEquals(0, image.getInt(PdfProbe.MATCH_COUNT))

            assertFalse(inspect(invalid, 0).getBoolean(PdfProbe.OPENED))
            assertTrue(inspect(pdf, 0).getBoolean(PdfProbe.OPENED))
        } finally {
            if (bound) context.unbindService(connection)
            repliesThread.quitSafely()
            sentinel.delete()
            invalid.delete()
            pdf.delete()
        }
    }

    private fun writeFixture(file: File) {
        val document = PdfDocument()
        try {
            val text = document.startPage(PdfDocument.PageInfo.Builder(96, 96, 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 12f
            }
            text.canvas.drawRect(2f, 2f, 24f, 24f, paint)
            text.canvas.drawText(PdfProbe.QUERY, 2f, 50f, paint)
            document.finishPage(text)

            val image = document.startPage(PdfDocument.PageInfo.Builder(96, 96, 2).create())
            val pixels = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            try {
                pixels.eraseColor(Color.BLACK)
                image.canvas.drawBitmap(pixels, 2f, 2f, null)
                document.finishPage(image)
                file.outputStream().use { document.writeTo(it) }
            } finally {
                pixels.recycle()
            }
        } finally {
            document.close()
        }
    }
}
