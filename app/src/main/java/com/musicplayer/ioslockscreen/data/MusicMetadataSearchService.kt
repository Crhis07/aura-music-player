package com.musicplayer.ioslockscreen.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resultado de búsqueda de metadatos desde el servicio online (iTunes Search API)
 */
data class OnlineMetadataResult(
    val trackTitle: String,
    val artistName: String,
    val albumName: String,
    val artworkUrl: String?,
    val releaseYear: String?,
    val genre: String?
)

/**
 * Servicio para consultar metadatos musicales en línea (títulos, artistas, álbumes y carátulas HD)
 * 100% MANUAL: Jamás se ejecuta en segundo plano ni de forma periódica.
 */
object MusicMetadataSearchService {

    fun isOnlineSearchDisabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("disable_all_online_search", false)
    }

    fun isWifiOnly(context: Context): Boolean {
        val prefs = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("wifi_only_metadata", true) // Por defecto: true (protección de datos móviles)
    }

    fun setWifiOnly(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("wifi_only_metadata", enabled).apply()
    }

    fun setOnlineSearchDisabled(context: Context, disabled: Boolean) {
        val prefs = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("disable_all_online_search", disabled).apply()
    }

    fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isMeteredConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    suspend fun search(query: String, context: Context? = null): List<OnlineMetadataResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        if (context != null) {
            if (isOnlineSearchDisabled(context)) {
                return@withContext emptyList()
            }
            if (isWifiOnly(context) && isMeteredConnection(context)) {
                // Bloqueado para proteger el plan de datos móviles del usuario
                return@withContext emptyList()
            }
        }

        val results = mutableListOf<OnlineMetadataResult>()
        var connection: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val urlString = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=10"
            val url = URL(urlString)

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "iOSMusicPlayer/1.2")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.use { it.readText() }
                val json = JSONObject(response)
                val items = json.optJSONArray("results") ?: return@withContext emptyList()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val trackTitle = item.optString("trackName")
                    val artistName = item.optString("artistName")
                    val albumName = item.optString("collectionName")
                    val rawArtwork = item.optString("artworkUrl100")
                    // Calidad Ultra HD: Convertir 100x100 a 1000x1000 para carátulas cristalinas
                    val artworkUrl = if (rawArtwork.isNotEmpty()) {
                        rawArtwork.replace("100x100bb.jpg", "1000x1000bb.jpg")
                    } else null
                    val releaseDate = item.optString("releaseDate")
                    val releaseYear = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                    val genre = item.optString("primaryGenreName")

                    if (trackTitle.isNotEmpty()) {
                        results.add(
                            OnlineMetadataResult(
                                trackTitle = trackTitle,
                                artistName = artistName,
                                albumName = albumName,
                                artworkUrl = artworkUrl,
                                releaseYear = releaseYear,
                                genre = genre
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection?.disconnect()
        }

        return@withContext results
    }
}
