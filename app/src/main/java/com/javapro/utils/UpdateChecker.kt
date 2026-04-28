package com.javapro.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val latestVersion : String,
    val downloadUrl   : String,
    val releaseNotes  : String,
    val publishedAt   : String
)

sealed class UpdateResult {
    data class UpdateAvailable(val info: ReleaseInfo) : UpdateResult()
    object AlreadyLatest  : UpdateResult()
    object RateLimited    : UpdateResult()
    object NetworkError   : UpdateResult()
    object NoApkAsset     : UpdateResult()
}

object UpdateChecker {

    private const val GITHUB_OWNER = "sorumstylXjava"
    private const val GITHUB_REPO  = "UploadApk"
    private const val API_URL      = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    @Volatile private var hasChecked   = false
    @Volatile private var cachedResult : ReleaseInfo? = null

    suspend fun checkForUpdate(context: Context): ReleaseInfo? {
        if (hasChecked) return cachedResult
        val detail   = checkForUpdateDetailed(context)
        hasChecked   = true
        cachedResult = if (detail is UpdateResult.UpdateAvailable) detail.info else null
        return cachedResult
    }

    fun resetCheck() {
        hasChecked   = false
        cachedResult = null
    }

    suspend fun checkForUpdateDetailed(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod  = "GET"
                connectTimeout = 10_000
                readTimeout    = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty(
                    "User-Agent",
                    "JavaPro-Android/${context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"}"
                )
            }

            val code = connection.responseCode

            if (code == 403 || code == 429) { connection.disconnect(); return@withContext UpdateResult.RateLimited }
            if (code != 200)                { connection.disconnect(); return@withContext UpdateResult.NetworkError  }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json         = JSONObject(body)
            val tagName      = cleanVersion(json.getString("tag_name"))
            val releaseNotes = json.optString("body", "").take(400).trim()
            val publishedAt  = json.optString("published_at", "").take(10)

            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false))
                return@withContext UpdateResult.AlreadyLatest

            val assets = json.optJSONArray("assets")
            val apkUrl = if (assets != null && assets.length() > 0) {
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
                    ?.getString("browser_download_url")
            } else null

            if (apkUrl == null) return@withContext UpdateResult.NoApkAsset

            val currentVersion = cleanVersion(
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
            )

            if (isNewerVersion(tagName, currentVersion))
                UpdateResult.UpdateAvailable(ReleaseInfo(tagName, apkUrl, releaseNotes, publishedAt))
            else
                UpdateResult.AlreadyLatest

        } catch (e: Exception) {
            UpdateResult.NetworkError
        }
    }

    private fun cleanVersion(raw: String): String =
        raw.trim().trimStart('v', 'V').replace(Regex("[^0-9.]"), "").trim('.').ifBlank { "0" }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        return try {
            val r    = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val l    = local.split(".").map  { it.toIntOrNull() ?: 0 }
            val size = maxOf(r.size, l.size)
            for (i in 0 until size) {
                val rv = r.getOrElse(i) { 0 }
                val lv = l.getOrElse(i) { 0 }
                if (rv > lv) return true
                if (rv < lv) return false
            }
            false
        } catch (e: Exception) { false }
    }

    fun downloadAndInstall(
        context    : Context,
        url        : String,
        fileName   : String = "JavaPro-update.apk",
        onProgress : (Int) -> Unit,
        onFinished : () -> Unit,
        onError    : () -> Unit
    ) {
        try {
            val destFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (destFile.exists()) destFile.delete()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("JavaPro Update")
                setDescription("Downloading new version...")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", "JavaPro-Android")
            }

            val dm   = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val dlId = dm.enqueue(request)

            val progressThread = Thread {
                var downloading = true
                while (downloading) {
                    val q      = DownloadManager.Query().setFilterById(dlId)
                    val cursor = dm.query(q)
                    if (cursor.moveToFirst()) {
                        val statusCol     = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalCol      = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                        val status     = if (statusCol >= 0)     cursor.getInt(statusCol)      else -1
                        val downloaded = if (downloadedCol >= 0) cursor.getLong(downloadedCol) else 0L
                        val total      = if (totalCol >= 0)      cursor.getLong(totalCol)       else 1L

                        if (total > 0) onProgress((downloaded * 100L / total).toInt())

                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> downloading = false
                            DownloadManager.STATUS_FAILED     -> { downloading = false; onError(); return@Thread }
                        }
                    }
                    cursor.close()
                    Thread.sleep(300)
                }
                onFinished()
                installApk(context, destFile)
            }
            progressThread.isDaemon = true
            progressThread.start()

        } catch (e: Exception) {
            onError()
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context,
                "Error, silahkan install manual apk di folder download",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
