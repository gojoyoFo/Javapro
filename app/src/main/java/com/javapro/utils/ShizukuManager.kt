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

    fun bindService() {
        if (!isAvailable() || isBinding || service != null) return
        isBinding = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            isBinding = false
        }
    }

    fun unbindService() {
        try { Shizuku.unbindUserService(userServiceArgs, connection, true) } catch (e: Exception) {}
        service = null
        isBinding = false
    }

    fun runCommand(command: String): String {
        if (!isAvailable()) return ""
        if (service == null) {
            bindService()
            var waited = 0
            while (service == null && waited < 5000) {
                Thread.sleep(100)
                waited += 100
            }
        }
        return try {
            service?.runCommand(command) ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
