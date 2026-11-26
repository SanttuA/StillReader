package io.github.santtua.stillreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SharedMemory
import android.system.ErrnoException
import java.util.UUID

internal data class PageFrame(
    val index: Int,
    val requestedWidth: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val bitmap: Bitmap
)

internal class PdfRenderSession(
    context: Context,
    private val onOpened: (Int) -> Unit,
    private val onPage: (Int, PageFrame?) -> Unit,
    private val onSearchPage: (Int, PageSearchResult?) -> Unit,
    private val onFailure: (Int) -> Unit
) : AutoCloseable, ServiceConnection {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        fail(if (pending?.type == PdfRenderProtocol.SEARCH_PAGE) R.string.error_search_timeout
            else R.string.error_renderer_timeout)
    }
    private val reply = Messenger(Handler(Looper.getMainLooper()) {
        try {
            receive(it)
        } catch (_: BadParcelableException) {
            fail(R.string.error_renderer_stopped)
        }
        true
    })
    private var descriptor: ParcelFileDescriptor? = null
    private var remote: Messenger? = null
    private var bound = false
    private var closed = false
    private var count = 0
    private var workerUid = -1
    private var sequence = 0
    private var pending: Request? = null

    private data class Request(
        val id: Int,
        val type: Int,
        val index: Int = -1,
        val width: Int = 0
    )

    /** Takes ownership of the descriptor. All methods and callbacks use the main thread. */
    fun open(fd: ParcelFileDescriptor) {
        check(!closed && descriptor == null)
        descriptor = fd
        val intent = Intent(app, PdfRenderService::class.java)
        bound = try {
            app.bindIsolatedService(
                intent,
                Context.BIND_AUTO_CREATE,
                UUID.randomUUID().toString().replace("-", ""),
                app.mainExecutor,
                this
            )
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
        if (bound) watch() else fail(R.string.error_renderer_stopped)
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (closed) return
        val fd = descriptor ?: return
        remote = Messenger(service)
        descriptor = null
        fd.use {
            send(Request(++sequence, PdfRenderProtocol.OPEN), Bundle().apply {
                putParcelable(PdfRenderProtocol.DESCRIPTOR, fd)
            })
        }
    }

    override fun onServiceDisconnected(name: ComponentName) = fail(R.string.error_renderer_stopped)
    override fun onBindingDied(name: ComponentName) = fail(R.string.error_renderer_stopped)
    override fun onNullBinding(name: ComponentName) = fail(R.string.error_renderer_stopped)

    fun render(index: Int, width: Int): Boolean {
        if (closed || pending != null || index !in 0 until count) return false
        require(width in 1..PdfRenderProtocol.MAX_EDGE)
        send(Request(++sequence, PdfRenderProtocol.RENDER, index, width), Bundle().apply {
            putInt(PdfRenderProtocol.PAGE, index)
            putInt(PdfRenderProtocol.REQUEST_WIDTH, width)
        })
        return !closed
    }

    fun search(index: Int, query: String, checkText: Boolean): Boolean {
        if (closed || pending != null || index !in 0 until count) return false
        require(query.isNotBlank() && query.length <= PdfRenderProtocol.MAX_QUERY_LENGTH)
        send(Request(++sequence, PdfRenderProtocol.SEARCH_PAGE, index), Bundle().apply {
            putInt(PdfRenderProtocol.PAGE, index)
            putString(PdfRenderProtocol.QUERY, query)
            putBoolean(PdfRenderProtocol.CHECK_TEXT, checkText)
        })
        return !closed
    }

    private fun send(request: Request, payload: Bundle) {
        pending = request
        watch()
        try {
            checkNotNull(remote).send(Message.obtain(null, request.type, request.id, 0).apply {
                replyTo = reply
                data = payload
            })
        } catch (_: RemoteException) {
            fail(R.string.error_renderer_stopped)
        }
    }

    private fun receive(message: Message) {
        val payload = message.data
        payload.getParcelable(PdfRenderProtocol.MEMORY, SharedMemory::class.java).use { memory ->
            val request = pending
            if (closed || request == null || message.what != PdfRenderProtocol.RESULT ||
                message.arg1 != request.id || message.arg2 != request.type
            ) return
            val sender = message.sendingUid
            if (sender <= 0 || sender == Process.myUid() || (workerUid != -1 && workerUid != sender)) {
                fail(R.string.error_renderer_stopped)
                return
            }
            workerUid = sender
            pending = null
            handler.removeCallbacks(watchdog)
            val status = payload.getInt(PdfRenderProtocol.STATUS)
            if (request.type == PdfRenderProtocol.OPEN) {
                count = payload.getInt(PdfRenderProtocol.COUNT)
                when {
                    status == PdfRenderProtocol.OK && count in 1..PdfRenderProtocol.MAX_PAGES && memory == null ->
                        onOpened(count)
                    status == PdfRenderProtocol.ENCRYPTED -> fail(R.string.error_encrypted)
                    else -> fail(R.string.error_invalid_pdf)
                }
                return
            }

            if (request.type == PdfRenderProtocol.SEARCH_PAGE) {
                when {
                    memory != null -> fail(R.string.error_renderer_stopped)
                    status == PdfRenderProtocol.UNRENDERABLE_PAGE -> onSearchPage(request.index, null)
                    status != PdfRenderProtocol.OK -> fail(R.string.error_renderer_stopped)
                    else -> {
                        val result = decodeSearch(payload, request.index)
                        if (result == null) fail(R.string.error_renderer_stopped)
                        else onSearchPage(request.index, result)
                    }
                }
                return
            }

            if (status == PdfRenderProtocol.UNRENDERABLE_PAGE && memory == null) {
                onPage(request.index, null)
                return
            }
            if (status != PdfRenderProtocol.OK || memory == null) {
                fail(R.string.error_renderer_stopped)
                return
            }
            val frame = decode(memory, payload, request)
            if (frame == null) fail(R.string.error_renderer_stopped) else onPage(request.index, frame)
        }
    }

    private fun decodeSearch(payload: Bundle, page: Int): PageSearchResult? {
        val width = payload.getInt(PdfRenderProtocol.PAGE_WIDTH)
        val height = payload.getInt(PdfRenderProtocol.PAGE_HEIGHT)
        val counts = payload.getIntArray(PdfRenderProtocol.RECT_COUNTS) ?: return null
        val rects = payload.getFloatArray(PdfRenderProtocol.RECTS) ?: return null
        if (payload.getInt(PdfRenderProtocol.PAGE, -1) != page ||
            !PdfRenderProtocol.acceptsPageSize(width, height) ||
            counts.size > PdfRenderProtocol.MAX_HITS_PER_PAGE ||
            counts.any { it !in 0..PdfRenderProtocol.MAX_RECTS_PER_HIT } ||
            rects.size != counts.sum() * 4 ||
            rects.any { !it.isFinite() || it !in 0f..1f }
        ) return null

        var offset = 0
        val hits = counts.map { count ->
            val boxes = List(count) {
                MatchRect(rects[offset++], rects[offset++], rects[offset++], rects[offset++])
            }
            if (boxes.any { it.left >= it.right || it.top >= it.bottom }) return null
            DocumentHit(page, width, height, boxes)
        }
        return PageSearchResult(
            payload.getBoolean(PdfRenderProtocol.HAS_TEXT),
            payload.getBoolean(PdfRenderProtocol.TRUNCATED),
            hits
        )
    }

    private fun decode(memory: SharedMemory, payload: Bundle, request: Request): PageFrame? {
        val pageWidth = payload.getInt(PdfRenderProtocol.PAGE_WIDTH)
        val pageHeight = payload.getInt(PdfRenderProtocol.PAGE_HEIGHT)
        if (payload.getInt(PdfRenderProtocol.PAGE, -1) != request.index ||
            !PdfRenderProtocol.acceptsPageSize(pageWidth, pageHeight)
        ) return null
        val expected = PdfRenderProtocol.rasterSize(pageWidth, pageHeight, request.width)
        if (payload.getInt(PdfRenderProtocol.WIDTH) != expected.width ||
            payload.getInt(PdfRenderProtocol.HEIGHT) != expected.height ||
            PdfRenderProtocol.byteCount(expected.width, expected.height) != memory.size
        ) return null

        val buffer = try {
            memory.mapReadOnly()
        } catch (_: ErrnoException) {
            return null
        }
        try {
            val bitmap = Bitmap.createBitmap(expected.width, expected.height, Bitmap.Config.ARGB_8888)
            if (bitmap.byteCount != memory.size) {
                bitmap.recycle()
                return null
            }
            bitmap.copyPixelsFromBuffer(buffer)
            return PageFrame(request.index, request.width, pageWidth, pageHeight, bitmap)
        } finally {
            SharedMemory.unmap(buffer)
        }
    }

    private fun watch() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 15_000)
    }

    private fun fail(message: Int) {
        if (closed) return
        close()
        onFailure(message)
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(watchdog)
        pending = null
        descriptor?.close()
        descriptor = null
        remote = null
        if (bound) app.unbindService(this)
        bound = false
    }
}
