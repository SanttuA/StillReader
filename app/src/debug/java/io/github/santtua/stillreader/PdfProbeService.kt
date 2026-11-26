package io.github.santtua.stillreader

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal object PdfProbe {
    const val REQUEST = 1
    const val RESULT = 2
    const val DESCRIPTOR = "descriptor"
    const val PAGE = "page"
    const val OPENED = "opened"
    const val PAGE_COUNT = "pageCount"
    const val RENDERED = "rendered"
    const val HAS_TEXT = "hasText"
    const val MATCH_COUNT = "matchCount"
    const val HAS_MATCH_BOUNDS = "hasMatchBounds"
    const val WORKER_UID = "workerUid"
    const val PRIVATE_FILE_DENIED = "privateFileDenied"
    const val QUERY = "StillReader"
    const val PRIVATE_FILE = "pdf-probe-sentinel"
    const val MAX_FILE_BYTES = 1024L * 1024
    private const val MAX_PAGE_POINTS = 4096

    fun acceptsPageSize(width: Int, height: Int): Boolean =
        width in 1..MAX_PAGE_POINTS && height in 1..MAX_PAGE_POINTS
}

class PdfProbeService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var inbox: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("pdf-probe").apply { start() }
        inbox = Messenger(Handler(worker.looper, ::handleRequest))
    }

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handleRequest(message: Message): Boolean {
        val input = message.data
        val descriptor = input.getParcelable(PdfProbe.DESCRIPTOR, ParcelFileDescriptor::class.java)
            ?: return true
        descriptor.use { fd ->
            val reply = message.replyTo
            if (message.what != PdfProbe.REQUEST || message.sendingUid != applicationInfo.uid || reply == null) {
                return true
            }
            val result = try {
                inspect(fd, input.getInt(PdfProbe.PAGE, -1))
            } catch (_: IOException) {
                Bundle()
            } catch (_: ErrnoException) {
                Bundle()
            } catch (_: SecurityException) {
                Bundle()
            } catch (_: IllegalArgumentException) {
                Bundle()
            }
            result.putInt(PdfProbe.WORKER_UID, Process.myUid())
            result.putBoolean(PdfProbe.PRIVATE_FILE_DENIED, privateFileReadIsDenied())
            try {
                reply.send(Message.obtain(null, PdfProbe.RESULT).apply { data = result })
            } catch (_: RemoteException) {
                // The instrumented caller may have timed out or exited.
            }
        }
        return true
    }

    private fun inspect(fd: ParcelFileDescriptor, pageIndex: Int): Bundle {
        val flags = Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
        require(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
        require(fd.statSize in 1..PdfProbe.MAX_FILE_BYTES)
        val result = Bundle()
        val rendererFd = ParcelFileDescriptor.dup(fd.fileDescriptor)
        val renderer = try {
            PdfRenderer(rendererFd)
        } catch (failure: Exception) {
            rendererFd.close()
            throw failure
        }
        renderer.use {
            val pageCount = renderer.pageCount
            require(pageIndex in 0 until pageCount)
            renderer.openPage(pageIndex).use { page ->
                require(PdfProbe.acceptsPageSize(page.width, page.height))
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    result.putBoolean(PdfProbe.RENDERED, bitmap.getPixel(8, 8) != Color.WHITE)
                } finally {
                    bitmap.recycle()
                }
                val matches = page.searchText(PdfProbe.QUERY)
                result.putBoolean(PdfProbe.HAS_TEXT, page.textContents.any { it.text.isNotBlank() })
                result.putInt(PdfProbe.MATCH_COUNT, matches.size)
                result.putBoolean(
                    PdfProbe.HAS_MATCH_BOUNDS,
                    matches.any { match -> match.bounds.any { bounds -> !bounds.isEmpty } }
                )
            }
            result.putBoolean(PdfProbe.OPENED, true)
            result.putInt(PdfProbe.PAGE_COUNT, pageCount)
        }
        return result
    }

    private fun privateFileReadIsDenied(): Boolean {
        val file = File(applicationInfo.dataDir, "files/" + PdfProbe.PRIVATE_FILE)
        return try {
            file.inputStream().use { it.read() }
            false
        } catch (_: FileNotFoundException) {
            true
        } catch (_: SecurityException) {
            true
        }
    }
}
