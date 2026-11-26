package io.github.santtua.stillreader

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException

class PdfRenderService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var inbox: Messenger
    private var renderer: PdfRenderer? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("pdf-renderer").apply { start() }
        handler = Handler(worker.looper) { request ->
            try {
                handle(request)
            } catch (_: BadParcelableException) {
                // The client times out and closes an invalid session.
            }
            true
        }
        inbox = Messenger(handler)
    }

    override fun onBind(intent: Intent): IBinder = inbox.binder

    override fun onDestroy() {
        handler.post { renderer?.close() }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun handle(request: Message) {
        val reply = request.replyTo
        if (request.sendingUid != applicationInfo.uid || reply == null || request.arg1 <= 0) return

        val payload = when (request.what) {
            PdfRenderProtocol.OPEN -> open(request.data)
            PdfRenderProtocol.RENDER -> render(request.data)
            PdfRenderProtocol.SEARCH_PAGE -> search(request.data)
            else -> return
        }
        try {
            reply.send(Message.obtain(null, PdfRenderProtocol.RESULT, request.arg1, request.what).apply {
                data = payload
            })
        } catch (_: RemoteException) {
            // The reader may have closed while the native operation was running.
        } finally {
            payload.getParcelable(PdfRenderProtocol.MEMORY, SharedMemory::class.java)?.close()
        }
    }

    private fun open(data: Bundle): Bundle {
        val fd = data.getParcelable(PdfRenderProtocol.DESCRIPTOR, ParcelFileDescriptor::class.java)
            ?: return result(PdfRenderProtocol.UNREADABLE)
        return fd.use {
            if (renderer != null) return@use result(PdfRenderProtocol.UNREADABLE)
            try {
                val flags = Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
                if (flags and OsConstants.O_ACCMODE != OsConstants.O_RDONLY ||
                    !OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)
                ) {
                    return@use result(PdfRenderProtocol.UNREADABLE)
                }
                val owned = ParcelFileDescriptor.dup(fd.fileDescriptor)
                val document = try {
                    PdfRenderer(owned)
                } catch (failure: Exception) {
                    owned.close()
                    throw failure
                }
                val count = document.pageCount
                if (count !in 1..PdfRenderProtocol.MAX_PAGES) {
                    document.close()
                    return@use result(PdfRenderProtocol.UNREADABLE)
                }
                renderer = document
                result(PdfRenderProtocol.OK).apply { putInt(PdfRenderProtocol.COUNT, count) }
            } catch (_: SecurityException) {
                result(PdfRenderProtocol.ENCRYPTED)
            } catch (_: IOException) {
                result(PdfRenderProtocol.UNREADABLE)
            } catch (_: ErrnoException) {
                result(PdfRenderProtocol.UNREADABLE)
            } catch (_: IllegalArgumentException) {
                result(PdfRenderProtocol.UNREADABLE)
            }
        }
    }

    private fun render(data: Bundle): Bundle {
        val document = renderer ?: return result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        val index = data.getInt(PdfRenderProtocol.PAGE, -1)
        val width = data.getInt(PdfRenderProtocol.REQUEST_WIDTH)
        if (index !in 0 until document.pageCount || width !in 1..PdfRenderProtocol.MAX_EDGE) {
            return result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        }

        return try {
            document.openPage(index).use { page ->
                val size = PdfRenderProtocol.rasterSize(page.width, page.height, width)
                result(PdfRenderProtocol.OK).apply {
                    putInt(PdfRenderProtocol.PAGE, index)
                    putInt(PdfRenderProtocol.PAGE_WIDTH, page.width)
                    putInt(PdfRenderProtocol.PAGE_HEIGHT, page.height)
                    putInt(PdfRenderProtocol.WIDTH, size.width)
                    putInt(PdfRenderProtocol.HEIGHT, size.height)
                    putParcelable(PdfRenderProtocol.MEMORY, rasterize(page, size))
                }
            }
        } catch (_: IllegalArgumentException) {
            result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        } catch (_: IllegalStateException) {
            result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        } catch (_: ErrnoException) {
            result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        }
    }

    private fun search(data: Bundle): Bundle {
        val document = renderer ?: return result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        val index = data.getInt(PdfRenderProtocol.PAGE, -1)
        val query = data.getString(PdfRenderProtocol.QUERY)
        if (index !in 0 until document.pageCount || query.isNullOrBlank() ||
            query.length > PdfRenderProtocol.MAX_QUERY_LENGTH
        ) return result(PdfRenderProtocol.UNRENDERABLE_PAGE)

        return try {
            document.openPage(index).use { page ->
                val width = page.width
                val height = page.height
                require(PdfRenderProtocol.acceptsPageSize(width, height))
                val matches = page.searchText(query)
                val hasText = matches.isNotEmpty() || (data.getBoolean(PdfRenderProtocol.CHECK_TEXT) &&
                    page.textContents.any { it.text.isNotBlank() })
                val count = minOf(matches.size, PdfRenderProtocol.MAX_HITS_PER_PAGE)
                val counts = IntArray(count)
                val coordinates = FloatArray(count * PdfRenderProtocol.MAX_RECTS_PER_HIT * 4)
                var used = 0
                for (hit in 0 until count) {
                    for (rect in matches[hit].bounds.take(PdfRenderProtocol.MAX_RECTS_PER_HIT)) {
                        val left = (rect.left / width).coerceIn(0f, 1f)
                        val top = (rect.top / height).coerceIn(0f, 1f)
                        val right = (rect.right / width).coerceIn(0f, 1f)
                        val bottom = (rect.bottom / height).coerceIn(0f, 1f)
                        if (!(left < right && top < bottom)) continue
                        coordinates[used++] = left
                        coordinates[used++] = top
                        coordinates[used++] = right
                        coordinates[used++] = bottom
                        counts[hit]++
                    }
                }
                result(PdfRenderProtocol.OK).apply {
                    putInt(PdfRenderProtocol.PAGE, index)
                    putInt(PdfRenderProtocol.PAGE_WIDTH, width)
                    putInt(PdfRenderProtocol.PAGE_HEIGHT, height)
                    putBoolean(PdfRenderProtocol.HAS_TEXT, hasText)
                    putBoolean(PdfRenderProtocol.TRUNCATED, matches.size > count)
                    putIntArray(PdfRenderProtocol.RECT_COUNTS, counts)
                    putFloatArray(PdfRenderProtocol.RECTS, coordinates.copyOf(used))
                }
            }
        } catch (_: IllegalArgumentException) {
            result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        } catch (_: IllegalStateException) {
            result(PdfRenderProtocol.UNRENDERABLE_PAGE)
        }
    }

    private fun rasterize(page: PdfRenderer.Page, size: RasterSize): SharedMemory {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val bytes = checkNotNull(PdfRenderProtocol.byteCount(size.width, size.height))
            check(bitmap.byteCount == bytes)
            val memory = SharedMemory.create("pdf-page", bytes)
            try {
                val writable = memory.mapReadWrite()
                try {
                    bitmap.copyPixelsToBuffer(writable)
                } finally {
                    SharedMemory.unmap(writable)
                }
                check(memory.setProtect(OsConstants.PROT_READ))
                return memory
            } catch (failure: Exception) {
                memory.close()
                throw failure
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun result(status: Int): Bundle =
        Bundle().apply { putInt(PdfRenderProtocol.STATUS, status) }
}
