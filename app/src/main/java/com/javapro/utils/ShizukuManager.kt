package com.javapro.utils

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.javapro.IShizukuService
import rikka.shizuku.Shizuku

object ShizukuManager {

    private var service: IShizukuService? = null
    private var isBinding = false

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.javapro", ShizukuUserService::class.java.name)
    ).daemon(false).processNameSuffix("service").debuggable(false).version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShizukuService.Stub.asInterface(binder)
            isBinding = false
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBinding = false
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** Apakah service AIDL sudah siap dipakai */
    fun isServiceReady(): Boolean = service != null

    fun bindService() {
        if (!isAvailable() || isBinding || service != null) return
        isBinding = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            isBinding = false
        }
    }

    /**
     * Pastikan service sudah bind. Panggil ini sekali di awal app (MainActivity/HomeScreen).
     * Non-blocking — tidak menunggu bind selesai.
     */
    fun ensureBound() {
        if (isAvailable() && service == null && !isBinding) {
            bindService()
        }
    }

    fun unbindService() {
        try { Shizuku.unbindUserService(userServiceArgs, connection, true) } catch (e: Exception) {}
        service = null
        isBinding = false
    }

    fun runCommand(command: String): String {
        if (!isAvailable()) return ""

        // Jika service belum ready, coba bind dulu dan tunggu maksimal 3 detik
        if (service == null) {
            if (!isBinding) bindService()
            var waited = 0
            while (service == null && waited < 3000) {
                Thread.sleep(100)
                waited += 100
            }
        }

        return try {
            service?.runCommand(command) ?: ""
        } catch (e: Exception) {
            service = null // reset agar bind ulang di call berikutnya
            ""
        }
    }
}

