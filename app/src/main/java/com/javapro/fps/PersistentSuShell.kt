package com.javapro.fps

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PersistentSuShell {

    private val TAG = "PersistentSuShell"

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerThread: Thread? = null

    // Baris output masuk dari reader thread
    private val lineQueue = LinkedBlockingQueue<String>(4096)

    // Lock agar hanya satu execute() berjalan bersamaan
    private val executeLock = ReentrantLock()

    // Sentinel unik — menandai akhir output setiap perintah
    private val SENTINEL = "---SU_DONE_${System.nanoTime()}---"

    @Volatile var connected = false
        private set

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Buka koneksi ke su. Timeout inisialisasi 2 detik.
     * Return true jika berhasil.
     */
    fun connect(): Boolean {
        close()  // pastikan state bersih sebelum reconnect
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            process = p

            writer = BufferedWriter(OutputStreamWriter(p.outputStream), 8192)

            // Drain stderr di daemon thread — kalau tidak, buffer full bisa block su
            Thread { drainStream(p.errorStream) }
                .also { it.isDaemon = true; it.name = "SuShell-stderr" }
                .start()

            // Reader thread: terus baca baris dari stdout dan taruh di queue
            readerThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(p.inputStream), 8192)
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break  // null = stream tutup
                        lineQueue.offer(line, 50, TimeUnit.MILLISECONDS)
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    Log.w(TAG, "Reader thread ended: ${e.message}")
                } finally {
                    connected = false
                }
            }.also {
                it.isDaemon = true
                it.name = "SuShell-reader"
                it.start()
            }

            // Test koneksi: kirim echo, tunggu max 2 detik
            val testResult = executeInternal("echo __su_ready__", timeoutMs = 2000L)
            connected = testResult.contains("__su_ready__")

            if (connected) {
                Log.i(TAG, "PersistentSuShell connected OK")
            } else {
                Log.w(TAG, "PersistentSuShell connect test failed (output='$testResult')")
                close()
            }
            connected
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}")
            close()
            false
        }
    }

    /**
     * Kirim [cmd] ke shell dan tunggu output sampai timeout.
     * Thread-safe: hanya satu perintah berjalan bersamaan.
     */
    fun execute(cmd: String, timeoutMs: Long = 1500L): String {
        if (!connected) return ""
        return executeLock.withLock {
            executeInternal(cmd, timeoutMs)
        }
    }

    fun isAlive(): Boolean {
        val p = process ?: return false
        return try { p.exitValue(); false }      // exitValue() throw = masih hidup
        catch (_: IllegalThreadStateException) { true }
    }

    fun close() {
        connected = false
        readerThread?.interrupt()
        readerThread = null
        writer?.runCatching { close() }
        process?.runCatching { destroyForcibly() }
        writer  = null
        process = null
        lineQueue.clear()
    }

    private fun executeInternal(cmd: String, timeoutMs: Long): String {
        val w = writer ?: return ""
        return try {
            // Bersihkan sisa output command sebelumnya
            lineQueue.clear()

            // Kirim command + sentinel ke stdin su
            // 2>/dev/null: stderr command diabaikan di dalam shell
            w.write("{ $cmd; } 2>/dev/null; echo '$SENTINEL'\n")
            w.flush()

            // Poll queue sampai sentinel atau timeout
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break

                val line = lineQueue.poll(minOf(remaining, 100L), TimeUnit.MILLISECONDS)
                    ?: continue   // timeout poll pendek, cek deadline lagi

                if (line == SENTINEL) break
                sb.append(line).append('\n')
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.w(TAG, "executeInternal error: ${e.message}")
            connected = false
            ""
        }
    }

    private fun drainStream(stream: InputStream) {
        try {
            val buf = ByteArray(2048)
            while (true) { if (stream.read(buf) < 0) break }
        } catch (_: Exception) {}
    }
}
