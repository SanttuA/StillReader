package io.github.santtua.stillreader

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class BackupFile(context: Context, private val uri: Uri) {
    private val resolver = context.applicationContext.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val signal = CancellationSignal()

    fun read(reply: (List<BackupBook>?) -> Unit) = run(reply) {
        val descriptor = open("r")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use(LibraryBackup::decode)
    }

    fun write(books: List<BackupBook>, reply: (Unit?) -> Unit) = run(reply) {
        val bytes = LibraryBackup.encode(books)
        val descriptor = open("wt")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes) }
    }

    private fun open(mode: String): ParcelFileDescriptor {
        signal.throwIfCanceled()
        val descriptor = resolver.openFileDescriptor(uri, mode, signal) ?: throw IOException()
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode)) throw IOException()
            signal.throwIfCanceled()
            return descriptor
        } catch (failure: Exception) {
            descriptor.close()
            throw failure
        }
    }

    private fun <T : Any> run(reply: (T?) -> Unit, operation: () -> T) {
        var waiting = true
        val timeout = Runnable {
            if (waiting) {
                waiting = false
                reply(null)
                signal.cancel()
            }
        }
        handler.postDelayed(timeout, 15_000)
        try {
            worker.execute {
                val result = try {
                    operation()
                } catch (_: IOException) {
                    null
                } catch (_: ErrnoException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                handler.post {
                    if (waiting) {
                        waiting = false
                        handler.removeCallbacks(timeout)
                        reply(result)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            handler.removeCallbacks(timeout)
            reply(null)
        }
    }

    private companion object {
        val worker = ThreadPoolExecutor(
            0, 1, 20, TimeUnit.SECONDS, SynchronousQueue(),
            { job -> Thread(job, "backup-file-access") }
        )
    }
}
