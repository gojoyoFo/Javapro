@file:Suppress("UNUSED", "PackageDirectoryMismatch")

package android.window

abstract class TaskFpsCallback {
    abstract fun onFpsReported(fps: Float)
}
