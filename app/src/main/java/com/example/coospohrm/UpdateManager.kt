package com.example.coospohrm

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val size: Long = 0,
    val changelog: String = ""
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API = "https://api.github.com/repos/oditynet/coospohrm/releases/latest"
        private const val CURRENT_VERSION = "1.4.2"
    }

    private var downloadId: Long = -1
    private var onUpdateAvailable: ((UpdateInfo) -> Unit)? = null
    private var onNoUpdate: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                installApk()
            }
        }
    }

    @SuppressLint("ServiceCast")
    fun checkForUpdate(
        onAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: () -> Unit,
        onError: (String) -> Unit
    ) {
        this.onUpdateAvailable = onAvailable
        this.onNoUpdate = onNoUpdate
        this.onError = onError

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork

        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            onError("Дайте разрешение сети в настройках->Приложения->coospohrm->network")
            return
        }
        Thread {
            try {
                val url = URL(GITHUB_API)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "CoospoHRM")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().readText()
                    val release = JSONObject(json)
                    val tagName = release.getString("tag_name")
                        .replace(Regex("^[vV](er_|er)?"), "")

                    // Сравниваем версии численно
                    if (compareVersions(tagName, CURRENT_VERSION) > 0) {
                        val assets = release.getJSONArray("assets")
                        if (assets.length() > 0) {
                            val asset = assets.getJSONObject(0)
                            val downloadUrl = asset.getString("browser_download_url")
                            val size = asset.getLong("size")
                            val body = release.optString("body", "")

                            val info = UpdateInfo(
                                version = tagName,
                                downloadUrl = downloadUrl,
                                size = size,
                                changelog = body
                            )

                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onUpdateAvailable?.invoke(info)
                            }
                        } else {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onNoUpdate?.invoke()
                            }
                        }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onNoUpdate?.invoke()
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onError?.invoke(e.message ?: "Неизвестная ошибка")
                }
            }
        }.start()
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val p1 = if (i < parts1.size) parts1[i] else 0
            val p2 = if (i < parts2.size) parts2[i] else 0
            if (p1 > p2) return 1   // v1 новее
            if (p1 < p2) return -1  // v1 старее
        }
        return 0 // равны
    }

    fun downloadAndInstall(url: String) {
        Toast.makeText(context, "Скачивание началось...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val file = File(context.cacheDir, "update.apk")

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "CoospoHRM")
                connection.connect()

                if (connection.responseCode != 200) {
                    postMain { Toast.makeText(context, "Ошибка сервера: ${connection.responseCode}", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                val totalSize = connection.contentLength
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                    }
                }
                connection.disconnect()

                if (file.exists() && file.length() > 0) {
                    postMain { installApk(file) }
                } else {
                    postMain { Toast.makeText(context, "Файл не скачался", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                postMain { Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Разрешите установку из неизвестных источников", Toast.LENGTH_LONG).show()
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    settingsIntent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(settingsIntent)
                    return
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun postMain(runnable: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(runnable)
    }

    private fun installApk() {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "coospohrm-update.apk")
            if (!file.exists()) {
                Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Разрешите установку из неизвестных источников", Toast.LENGTH_LONG).show()
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    settingsIntent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(settingsIntent)
                    return
                }
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun destroy() {
        try {
            context.unregisterReceiver(downloadReceiver)
        } catch (_: Exception) {}
    }
}