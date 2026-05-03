package com.javapro.workers

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.*
import com.javapro.utils.TweakExecutor
import java.util.concurrent.TimeUnit

class AutoRamWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val isRoot = TweakExecutor.checkRoot()
            if (isRoot) {
                TweakExecutor.execute("echo 3 > /proc/sys/vm/drop_caches")
                TweakExecutor.execute("echo 1 > /proc/sys/vm/drop_caches")
                TweakExecutor.execute("echo 2 > /proc/sys/vm/drop_caches")
            }
            val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses
                ?.filter { it.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE }
                ?.forEach { Runtime.getRuntime().gc() }
            System.gc()
            System.runFinalization()
            Log.d(TAG, "AutoRamWorker completed, root=$isRoot")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "AutoRamWorker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutoRamWorker"
        const val WORK_NAME = "auto_ram_cleaner"

        fun schedule(context: Context, intervalMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<AutoRamWorker>(
                intervalMinutes.toLong(), TimeUnit.MINUTES
            )
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Scheduled every $intervalMinutes min")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled")
        }

        fun isScheduled(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()
                .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
    }
}
