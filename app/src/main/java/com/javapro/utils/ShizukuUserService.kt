package com.javapro.utils

import com.javapro.IShizukuService

class ShizukuUserService : IShizukuService.Stub() {

    override fun runCommand(command: String): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            // Drain errorStream agar tidak block di beberapa ROM
            process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            ""
        } finally {
            process?.inputStream?.runCatching { close() }
            process?.errorStream?.runCatching { close() }
            process?.outputStream?.runCatching { close() }
            process?.destroy()
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
