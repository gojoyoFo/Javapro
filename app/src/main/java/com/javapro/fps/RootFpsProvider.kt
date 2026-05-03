package com.javapro.fps

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.LinkedList

/**
 * RootFpsProvider — Native Binary Edition
 *
 * Arsitektur:
 *  1. Extract fps_core + engine_launcher.sh dari assets ke /data/local/tmp/javapro/
 *  2. Jalankan engine_launcher.sh via PersistentSuShell sebagai streaming process
 *  3. Reader thread baca stdout binary line-by-line → update @Volatile latestFps
 *  4. getInstantFps() non-blocking, kembalikan nilai cached
 *
 * Fallback:
 *  - Kalau root/binary gagal → ShizukuFpsProvider (non-root via Shizuku)
 *  - Kalau Shizuku juga tidak ada → 0f
 *
 * Tidak ada File() atau ActivityTaskManager di dalam flow utama.
 * Tidak ada hiddenapi call. Tidak ada avc:denied.
 */
class RootFpsProvider(
    private val context: Context,
    private val maxRefreshRate: Float
) : IFpsProvider {

    private val TAG = "RootFpsProvider"

    // Binary path di device
    private val BINARY_DIR     = "/data/local/tmp/javapro"
    private val BINARY_NAME    = "fps_core"
    private val LAUNCHER_NAME  = "engine_launcher.sh"

    // Persistent su shell untuk extract + launch
    private val shell = PersistentSuShell()

    // Streaming process binary fps_core
    @Volatile private var binaryProcess: Process? = null
    private var readerThread: Thread? = null

    // Cached FPS — ditulis oleh readerThread, dibaca oleh getInstantFps()
    @Volatile private var latestFps: Float = 0f
    @Volatile private var lastUpdateMs: Long = 0L

    // Non-root fallback
    private var shizukuFallback: IFpsProvider? = null
    @Volatile private var useShizukuFallback = false

    // SMA 8 sampel
    private val smaBuffer = LinkedList<Float>()
    private val SMA_SIZE  = 8

    companion object {
        private const val STALE_MS     = 3000L   // anggap mati kalau tidak ada output > 3 detik
        private const val BINARY_ASSET = "fps_core"   // nama di assets/ (tanpa ekstensi)
        private const val LAUNCHER_ASSET = "engine_launcher.sh"
    }

    init {
        val ok = setupAndLaunch()
        if (!ok) {
            Log.w(TAG, "Root binary failed, switching to Shizuku fallback")
            useShizukuFallback = true
            shizukuFallback    = ShizukuFpsProvider(maxRefreshRate)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    override fun getInstantFps(): Float {
        if (useShizukuFallback) {
            return shizukuFallback?.getInstantFps() ?: 0f
        }

        // Cek apakah binary masih hidup
        val now = System.currentTimeMillis()
        if (lastUpdateMs > 0L && now - lastUpdateMs > STALE_MS) {
            Log.w(TAG, "fps_core stream stale, restarting")
            restartBinary()
        }

        return latestFps
    }

    override fun release() {
        stopBinary()
        shell.close()
        shizukuFallback?.release()
        shizukuFallback = null
        smaBuffer.clear()
        latestFps = 0f
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupAndLaunch(): Boolean {
        if (!shell.connect()) {
            Log.w(TAG, "PersistentSuShell connect failed")
            return false
        }

        // Buat direktori
        shell.execute("mkdir -p $BINARY_DIR", timeoutMs = 2000L)

        // Extract binary dari assets
        if (!extractAsset(BINARY_ASSET, "$BINARY_DIR/$BINARY_NAME")) return false
        if (!extractAsset(LAUNCHER_ASSET, "$BINARY_DIR/$LAUNCHER_NAME")) return false

        // chmod +x
        shell.execute("chmod +x $BINARY_DIR/$BINARY_NAME $BINARY_DIR/$LAUNCHER_NAME", timeoutMs = 1000L)

        // Launch binary sebagai streaming process
        return launchBinaryStream()
    }

    private fun extractAsset(assetName: String, destPath: String): Boolean {
        return try {
            val destFile = File(destPath)

            // Cek apakah sudah ada dan ukuran sama (skip extract kalau sudah update)
            val assetSize = context.assets.open(assetName).use { it.available().toLong() }
            if (destFile.exists() && destFile.length() == assetSize) {
                Log.d(TAG, "Asset $assetName already extracted, skipping")
                return true
            }

            // Tulis ke tmp dulu lalu mv agar atomic
            val tmpPath = "$destPath.tmp"
            context.assets.open(assetName).use { input ->
                File(tmpPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // mv via shell (pastikan permission benar)
            val mv = shell.execute("mv $tmpPath $destPath && echo ok", timeoutMs = 2000L)
            val success = mv.contains("ok")
            if (!success) {
                Log.e(TAG, "Failed to move $tmpPath → $destPath")
            } else {
                Log.i(TAG, "Extracted asset: $assetName → $destPath")
            }
            success
        } catch (e: IOException) {
            Log.e(TAG, "extractAsset $assetName failed: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "extractAsset $assetName error: ${e.message}")
            false
        }
    }

    // ── Binary Streaming ──────────────────────────────────────────────────────

    private fun launchBinaryStream(): Boolean {
        stopBinary()
        return try {
            // Jalankan via su langsung sebagai streaming process
            // Tidak pakai PersistentSuShell.execute() karena itu blocking dengan sentinel
            // Pakai Runtime.exec() dengan su agar stream tetap terbuka
            val proc = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "sh $BINARY_DIR/$LAUNCHER_NAME")
            )
            binaryProcess = proc

            // Drain stderr
            Thread {
                try {
                    val buf = ByteArray(1024)
                    while (proc.errorStream.read(buf) >= 0) {}
                } catch (_: Exception) {}
            }.also { it.isDaemon = true; it.name = "fps_core-stderr"; it.start() }

            // Reader thread: baca stdout line-by-line
            readerThread = Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break  // null = proses mati
                        val fps = line.trim().toFloatOrNull() ?: continue
                        if (fps > 0f) {
                            latestFps     = applySma(fps.coerceAtMost(maxRefreshRate))
                            lastUpdateMs  = System.currentTimeMillis()
                        } else {
                            // fps_core output "0" saat tidak ada frame
                            latestFps = 0f
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fps_core reader ended: ${e.message}")
                } finally {
                    if (!useShizukuFallback) {
                        Log.w(TAG, "fps_core stream ended, will restart on next stale check")
                    }
                }
            }.also {
                it.isDaemon = true
                it.name     = "fps_core-reader"
                it.start()
            }

            lastUpdateMs = System.currentTimeMillis()
            Log.i(TAG, "fps_core streaming started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "launchBinaryStream failed: ${e.message}")
            false
        }
    }

    private fun stopBinary() {
        readerThread?.interrupt()
        readerThread = null
        binaryProcess?.runCatching { destroyForcibly() }
        binaryProcess = null
    }

    private fun restartBinary() {
        val ok = launchBinaryStream()
        if (!ok) {
            Log.e(TAG, "Restart failed, switching to Shizuku")
            useShizukuFallback = true
            if (shizukuFallback == null) shizukuFallback = ShizukuFpsProvider(maxRefreshRate)
        }
    }

    // ── SMA ───────────────────────────────────────────────────────────────────

    @Synchronized
    private fun applySma(raw: Float): Float {
        smaBuffer.addLast(raw)
        if (smaBuffer.size > SMA_SIZE) smaBuffer.removeFirst()
        return smaBuffer.average().toFloat()
    }
}
