package com.javapro.shell

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

class ShellDaemon(
    private val onOutput: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerThread: Thread? = null
    private var errorThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val mainScope = CoroutineScope(Dispatchers.Main)

    fun start() {
        if (isRunning.get()) return
        
        Thread {
            try {
                process = Runtime.getRuntime().exec("su")
                writer = OutputStreamWriter(process?.outputStream)
                
                val reader = BufferedReader(InputStreamReader(process?.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process?.errorStream))
                
                isRunning.set(true)
                
                // Thread untuk stdout
                readerThread = Thread {
                    try {
                        var line: String?
                        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                            line = reader.readLine()
                            if (line == null) break
                            mainScope.launch { onOutput(line) }
                        }
                    } catch (e: InterruptedException) {
                        // Normal exit
                    } catch (e: Exception) {
                        mainScope.launch { onError("Read error: ${e.message}") }
                    }
                }.apply { start() }
                
                // Thread untuk stderr
                errorThread = Thread {
                    try {
                        var line: String?
                        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                            line = errorReader.readLine()
                            if (line == null) break
                            mainScope.launch { onError(line) }
                        }
                    } catch (e: InterruptedException) {
                        // Normal exit
                    } catch (e: Exception) {
                        // Ignore
                    }
                }.apply { start() }
                
                mainScope.launch { onOutput("Shell Ready") }
                
            } catch (e: Exception) {
                mainScope.launch { onError("Shell error: ${e.message}") }
            }
        }.start()
    }

    fun exec(command: String) {
        Thread {
            try {
                writer?.write("$command\n")
                writer?.flush()
            } catch (e: Exception) {
                mainScope.launch { onError("Exec error: ${e.message}") }
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        readerThread?.interrupt()
        errorThread?.interrupt()
        process?.destroyForcibly()
        mainScope.cancel()
    }

    fun restart() {
        stop()
        Thread.sleep(200)
        start()
    }
}
