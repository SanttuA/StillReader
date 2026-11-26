package io.github.santtua.stillreader

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PdfDocumentAccess(
    context: Context,
    private val uri: Uri,
    private val savedCopy: File?,
    private val makeCopy: Boolean,
    onResult: (Result) -> Unit
) : AutoCloseable {
    sealed interface Result {
        data class Open(
            val descriptor: ParcelFileDescriptor,
            val displayName: String?,
            val draft: File?
        ) : Result {
            fun discard() {
                try {
                    descriptor.close()
                } catch (_: IOException) {
                    // The provider descriptor may already be gone.
                } finally {
                    draft?.delete()
                }
            }
        }
        data class Failure(val message: Int) : Result
    }

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val signal = CancellationSignal()
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ((Result) -> Unit)? = onResult
    private var future: Future<*>? = null
    private val timeout = Runnable {
        val listener = callback
        close()
        listener?.invoke(Result.Failure(R.string.error_document_timeout))
    }

    fun begin() {
        handler.postDelayed(timeout, 15_000)
        try {
            future = worker.submit {
                val result = open()
                handler.post {
                    val listener = callback
                    callback = null
                    handler.removeCallbacks(timeout)
                    if (listener != null) listener(result)
                    else if (result is Result.Open) result.discard()
                }
            }
        } catch (_: RejectedExecutionException) {
            val listener = callback
            close()
            listener?.invoke(Result.Failure(R.string.error_document_access))
        }
    }

    private fun open(): Result {
        var draft: File? = null
        try {
            signal.throwIfCanceled()
            val title = if (savedCopy == null) readDisplayName() else null
            signal.throwIfCanceled()
            if (makeCopy) {
                val directory = File(app.noBackupFilesDir, "books")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                draft = File.createTempFile("draft-", ".pdf", directory)
                copySource(draft, directory)
            }
            val file = draft ?: savedCopy
            val descriptor = if (file != null) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                resolver.openFileDescriptor(uri, "r", signal)
            }
            if (descriptor == null) return Result.Failure(R.string.error_document_access)
            val result = validate(descriptor, title, draft)
            if (result is Result.Open) draft = null
            return result
        } catch (_: CopyTooLarge) {
            return Result.Failure(R.string.error_copy_space)
        } catch (_: SecurityException) {
            return Result.Failure(errorReading())
        } catch (_: IOException) {
            return Result.Failure(errorReading())
        } catch (_: IllegalArgumentException) {
            return Result.Failure(errorReading())
        } catch (_: OperationCanceledException) {
            return Result.Failure(errorReading())
        } finally {
            draft?.delete()
        }
    }

    private fun errorReading(): Int =
        if (makeCopy) R.string.error_copy_pdf else R.string.error_document_access

    private fun readDisplayName(): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)
            ?.use { if (it.moveToFirst()) cleanBookTitle(it.getString(0)) else null }
    } catch (canceled: OperationCanceledException) {
        throw canceled
    } catch (_: RuntimeException) {
        // A failing provider metadata query should not prevent opening the file.
        null
    }

    private fun copySource(draft: File, directory: File) {
        val maxBytes = minOf(MAX_COPY_BYTES, StatFs(directory.absolutePath).availableBytes - RESERVE_BYTES)
        if (maxBytes <= 0) throw CopyTooLarge()
        val source = resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException()
        source.use {
            if (source.length > maxBytes) throw CopyTooLarge()
            source.createInputStream().use { input ->
                draft.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        signal.throwIfCanceled()
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > maxBytes) throw CopyTooLarge()
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun validate(fd: ParcelFileDescriptor, title: String?, draft: File?): Result {
        var keep = false
        try {
            val flags = Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
            val stat = Os.fstat(fd.fileDescriptor)
            if (flags and OsConstants.O_ACCMODE != OsConstants.O_RDONLY ||
                !OsConstants.S_ISREG(stat.st_mode)
            ) return Result.Failure(R.string.error_seekable_pdf)

            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
            signal.throwIfCanceled()
            keep = true
            return Result.Open(fd, title, draft)
        } catch (_: ErrnoException) {
            return Result.Failure(R.string.error_seekable_pdf)
        } finally {
            if (!keep) fd.close()
        }
    }

    override fun close() {
        callback = null
        handler.removeCallbacks(timeout)
        signal.cancel()
        future?.cancel(true)
        worker.purge()
    }

    private class CopyTooLarge : IOException()

    private companion object {
        const val MAX_COPY_BYTES = 1024L * 1024 * 1024
        const val RESERVE_BYTES = 32L * 1024 * 1024
        val worker = ThreadPoolExecutor(
            1, 1, 20, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { job -> Thread(job, "pdf-file-access") }
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
