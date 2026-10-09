package com.kanealex.obdauto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Serialised ELM327 command queue. Replies are buffered until the '>' prompt. */
class Elm327 {
    var transport: Transport? = null
    private val mutex = Mutex()
    private val buffer = StringBuilder()
    @Volatile private var pending: CompletableDeferred<String>? = null

    fun onData(bytes: ByteArray) {
        synchronized(buffer) {
            for (b in bytes) {
                val ch = (b.toInt() and 0xff).toChar()
                if (ch == '>') {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    pending?.complete(text)
                } else {
                    buffer.append(ch)
                }
            }
        }
    }

    suspend fun send(cmd: String, timeoutMs: Long = 2_000): String = mutex.withLock {
        val t = transport ?: throw IOException("Not connected")
        synchronized(buffer) { buffer.setLength(0) }
        val d = CompletableDeferred<String>()
        pending = d
        try {
            t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            withTimeout(timeoutMs) { d.await() }
        } finally {
            pending = null
        }
    }

    companion object {
        /** Non-empty reply lines, without echo or "SEARCHING...". */
        fun lines(resp: String): List<String> =
            resp.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }

        /** Data bytes after "41 <pid>", or null if the reply has none (NO DATA, error, etc.). */
        fun parsePid(resp: String, pid: String, n: Int): IntArray? {
            val hex = lines(resp)
                .map { it.replace(" ", "").uppercase() }
                .filter { line -> line.all { it in '0'..'9' || it in 'A'..'F' } }
                .joinToString("")
            val i = hex.indexOf("41$pid")
            if (i < 0) return null
            val data = hex.substring(i + 4)
            if (data.length < n * 2) return null
            return IntArray(n) { data.substring(it * 2, it * 2 + 2).toInt(16) }
        }
    }
}
