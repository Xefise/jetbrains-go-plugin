package io.github.xefise.golsp.execution

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The console's stdin for a program that does not exist yet: buffers input until [connect], then forwards it.
 * Pipe writes go through one background queue, so neither typing nor termination waits on a program that never reads.
 */
internal class GoProgramInput : OutputStream() {
    private val lock = Any()
    private val queue = AppExecutorUtil.createBoundedApplicationPoolExecutor("Go program stdin", 1)
    private var pending: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var target: OutputStream? = null
    private var closed = false

    /** Only the first call counts; null means the program will not run and buffered input is dropped. */
    fun connect(stream: OutputStream?) {
        val (buffered, closeAfter) = synchronized(lock) {
            val data = pending?.toByteArray() ?: return
            pending = null
            if (!closed) target = stream
            data to closed
        }
        if (stream == null) return
        enqueue(stream) {
            it.write(buffered)
            it.flush()
            if (closeAfter) it.close()
        }
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val out = synchronized(lock) {
            if (closed) return
            pending?.let {
                it.write(b, off, len)
                return
            }
            target
        } ?: return
        val copy = b.copyOfRange(off, off + len)
        enqueue(out) {
            it.write(copy)
            it.flush()
        }
    }

    override fun close() {
        val out = synchronized(lock) {
            if (closed) return
            closed = true
            target
        } ?: return
        enqueue(out, OutputStream::close)
    }

    private fun enqueue(stream: OutputStream, action: (OutputStream) -> Unit) {
        queue.execute {
            try {
                action(stream)
            } catch (e: IOException) {
                thisLogger().info("Program stdin is gone: ${e.message}")
            }
        }
    }
}
