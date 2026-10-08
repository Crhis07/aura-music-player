package com.musicplayer.ioslockscreen.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val changelog: String,
    val forceUpdate: Boolean = false,
    val hasUpdate: Boolean = false
)

object AppUpdateManager {

    private const val PREFS_NAME = "app_update_prefs"
    private const val KEY_DISMISSED_VERSION = "dismissed_version_code"
    
    // URL directa al archivo version.json en la rama principal de GitHub
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/Crhis07/aura-music-player/main/version.json"

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    fun dismissVersion(context: Context, versionCode: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DISMISSED_VERSION, versionCode).apply()
    }

    fun getDismissedVersionCode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DISMISSED_VERSION, -1)
    }

    suspend fun checkUpdate(
        context: Context,
        isManualCheck: Boolean = false
    ): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        try {
            // Verificación de restricciones de datos móviles y modo sin conexión
            if (MusicMetadataSearchService.isOnlineSearchDisabled(context)) {
                return@withContext if (isManualCheck) {
                    Result.failure(Exception("El 'Modo Sin Conexión Total' está activado en Ajustes."))
                } else {
                    Result.failure(Exception("Online search disabled"))
                }
            }

            if (MusicMetadataSearchService.isWifiOnly(context) && MusicMetadataSearchService.isMeteredConnection(context)) {
                return@withContext if (isManualCheck) {
                    Result.failure(Exception("Bloqueado: 'Solo Wi-Fi' está activo y estás conectado a datos móviles."))
                } else {
                    Result.failure(Exception("Metered connection"))
                }
            }

            val url = URL(VERSION_JSON_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "AuraMusicPlayer/2.0")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("Servidor respondió con código ${connection.responseCode}"))
            }

            val rawJson = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(rawJson)

            val remoteCode = json.optInt("versionCode", 0)
            val remoteName = json.optString("versionName", "1.0.0")
            val downloadUrl = json.optString("downloadUrl", "")
            val changelog = json.optString("changelog", "Mejoras generales y corrección de errores.")
            val forceUpdate = json.optBoolean("forceUpdate", false)

            val currentCode = getCurrentVersionCode(context)
            var hasUpdate = remoteCode > currentCode

            // Si es verificación automática en segundo plano y el usuario ya rechazó esta versión
            if (!isManualCheck && !forceUpdate && hasUpdate) {
                val dismissed = getDismissedVersionCode(context)
                if (dismissed == remoteCode) {
                    hasUpdate = false
                }
            }

            val info = AppUpdateInfo(
                versionCode = remoteCode,
                versionName = remoteName,
                downloadUrl = downloadUrl,
                changelog = changelog,
                forceUpdate = forceUpdate,
                hasUpdate = hasUpdate
            )

            Result.success(info)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "AuraMusicPlayer/2.0")
                instanceFollowRedirects = true
            }

            // Manejo de redirecciones de GitHub releases (HTTP 301 / 302)
            var currentConnection = connection
            var responseCode = currentConnection.responseCode
            var redirects = 0
            while ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == 307 || responseCode == 308) && redirects < 5
            ) {
                val newUrl = currentConnection.getHeaderField("Location")
                currentConnection.disconnect()
                currentConnection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "AuraMusicPlayer/2.0")
                }
                responseCode = currentConnection.responseCode
                redirects++
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("Error al descargar: código HTTP $responseCode"))
            }

            val totalBytes = currentConnection.contentLengthLong
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val apkFile = File(updatesDir, "AuraMusic_update.apk")
            if (apkFile.exists()) apkFile.delete()

            currentConnection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            val progress = (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            // Verificar permiso para orígenes desconocidos si es Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
