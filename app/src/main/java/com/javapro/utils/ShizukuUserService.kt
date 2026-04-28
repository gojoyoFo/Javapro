package com.javapro.utils

import com.javapro.IShizukuService

class ShizukuUserService : IShizukuService.Stub() {

    override fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            ""
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
