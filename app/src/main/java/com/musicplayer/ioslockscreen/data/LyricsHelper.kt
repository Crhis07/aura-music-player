package com.musicplayer.ioslockscreen.data

import android.content.Context
import com.musicplayer.ioslockscreen.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val plainText: String? = null,
    val hasLyrics: Boolean = false,
    val source: String = "local" // "local", "cache", "online", "demo"
)

object LyricsHelper {

    private val timeRegex = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")

    fun isAutoLyricsDownloadEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("auto_lyrics_download", true)
    }

    fun setAutoLyricsDownload(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("network_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_lyrics_download", enabled).apply()
    }

    suspend fun loadLyricsForSong(context: Context, song: Song): LyricsResult = withContext(Dispatchers.IO) {
        // 1. Si la canción tiene ruta física (filePath), buscar archivo .lrc en la misma carpeta
        if (song.filePath.isNotBlank()) {
            val file = File(song.filePath)
            val parent = file.parentFile
            val baseName = file.nameWithoutExtension

            if (parent != null && parent.exists()) {
                val lrcFile = File(parent, "$baseName.lrc")
                if (lrcFile.exists() && lrcFile.canRead()) {
                    val parsed = parseLrc(lrcFile.readText())
                    if (parsed.isNotEmpty()) {
                        return@withContext LyricsResult(isSynced = true, lines = parsed, hasLyrics = true, source = "local")
                    }
                }

                val txtFile = File(parent, "$baseName.txt")
                if (txtFile.exists() && txtFile.canRead()) {
                    val content = txtFile.readText().trim()
                    if (content.isNotEmpty()) {
                        val parsed = parseLrc(content)
                        return@withContext if (parsed.isNotEmpty()) {
                            LyricsResult(isSynced = true, lines = parsed, hasLyrics = true, source = "local")
                        } else {
                            LyricsResult(isSynced = false, lines = emptyList(), plainText = content, hasLyrics = true, source = "local")
                        }
                    }
                }
            }
        }

        // 2. Buscar en caché interna de la app (filesDir/lyrics/) -> 0 consumo de datos
        val cacheDir = File(context.filesDir, "lyrics")
        if (cacheDir.exists()) {
            val cachedLrc = File(cacheDir, "lyrics_${song.id}.lrc")
            if (cachedLrc.exists() && cachedLrc.canRead()) {
                val parsed = parseLrc(cachedLrc.readText())
                if (parsed.isNotEmpty()) {
                    return@withContext LyricsResult(isSynced = true, lines = parsed, hasLyrics = true, source = "cache")
                }
            }

            val cachedTxt = File(cacheDir, "lyrics_${song.id}.txt")
            if (cachedTxt.exists() && cachedTxt.canRead()) {
                val content = cachedTxt.readText().trim()
                if (content.isNotEmpty()) {
                    return@withContext LyricsResult(isSynced = false, lines = emptyList(), plainText = content, hasLyrics = true, source = "cache")
                }
            }
        }

        // 3. Para canciones de demostración
        if (song.isDemo) {
            val demoLrc = getDemoLyrics(song.id)
            if (demoLrc.isNotEmpty()) {
                return@withContext LyricsResult(isSynced = true, lines = demoLrc, hasLyrics = true, source = "demo")
            }
        }

        // 4. Si la descarga automática está habilitada y las reglas de red lo permiten:
        if (isAutoLyricsDownloadEnabled(context)) {
            val canUseNetwork = !MusicMetadataSearchService.isOnlineSearchDisabled(context) &&
                    (!MusicMetadataSearchService.isWifiOnly(context) || !MusicMetadataSearchService.isMeteredConnection(context))

            if (canUseNetwork) {
                val onlineResult = fetchLyricsFromOnline(context, song)
                if (onlineResult.hasLyrics) {
                    return@withContext onlineResult
                }
            }
        }

        return@withContext LyricsResult(
            isSynced = false,
            lines = emptyList(),
            plainText = null,
            hasLyrics = false
        )
    }

    /**
     * Búsqueda manual forzada en línea (iniciada con botón del usuario)
     */
    suspend fun fetchOnlineLyricsManually(context: Context, song: Song): LyricsResult = withContext(Dispatchers.IO) {
        if (MusicMetadataSearchService.isOnlineSearchDisabled(context)) {
            return@withContext LyricsResult(isSynced = false, lines = emptyList(), hasLyrics = false)
        }
        if (MusicMetadataSearchService.isWifiOnly(context) && MusicMetadataSearchService.isMeteredConnection(context)) {
            return@withContext LyricsResult(isSynced = false, lines = emptyList(), hasLyrics = false)
        }

        return@withContext fetchLyricsFromOnline(context, song)
    }

    private fun fetchLyricsFromOnline(context: Context, song: Song): LyricsResult {
        try {
            val baseTitle = song.title
                .replace(Regex("""\.(mp3|flac|wav|m4a|aac|ogg|wma)$""", RegexOption.IGNORE_CASE), "")
            var cleanTitle = baseTitle.replace(Regex("""\(.*?\)|\{.*?\}|\[.*?\]"""), "").trim()
            if (cleanTitle.isBlank()) cleanTitle = baseTitle.trim()

            val cleanArtist = if (song.artist.contains("Desconocido", ignoreCase = true) || song.artist.contains("Unknown", ignoreCase = true)) "" else song.artist.trim()

            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val durationSec = song.durationMs / 1000

            // 1. Intento por endpoint directo de LRCLIB
            val directUrl = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist&duration=$durationSec"
            var json: JSONObject? = queryLrcLib(directUrl)

            // 2. Si no coincide exactamente, búsqueda general por texto
            if (json == null || (!json.has("syncedLyrics") && !json.has("plainLyrics"))) {
                val searchQuery = URLEncoder.encode("$cleanTitle $cleanArtist".trim(), "UTF-8")
                val searchUrl = "https://lrclib.net/api/search?q=$searchQuery"
                val searchArray = queryLrcLibArray(searchUrl)
                if (searchArray != null && searchArray.length() > 0) {
                    var candidate: JSONObject? = null
                    for (i in 0 until searchArray.length()) {
                        val obj = searchArray.optJSONObject(i) ?: continue
                        val hasSynced = obj.optString("syncedLyrics", "").isNotBlank()
                        val hasPlain = obj.optString("plainLyrics", "").isNotBlank()
                        if (hasSynced) {
                            candidate = obj
                            break
                        } else if (hasPlain && candidate == null) {
                            candidate = obj
                        }
                    }
                    json = candidate ?: searchArray.optJSONObject(0)
                }
            }

            if (json != null) {
                val syncedLyrics = json.optString("syncedLyrics", "").trim()
                val plainLyrics = json.optString("plainLyrics", "").trim()

                val cacheDir = File(context.filesDir, "lyrics")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                if (syncedLyrics.isNotEmpty()) {
                    val parsed = parseLrc(syncedLyrics)
                    if (parsed.isNotEmpty()) {
                        File(cacheDir, "lyrics_${song.id}.lrc").writeText(syncedLyrics)
                        return LyricsResult(isSynced = true, lines = parsed, hasLyrics = true, source = "online")
                    }
                }

                if (plainLyrics.isNotEmpty()) {
                    File(cacheDir, "lyrics_${song.id}.txt").writeText(plainLyrics)
                    return LyricsResult(isSynced = false, lines = emptyList(), plainText = plainLyrics, hasLyrics = true, source = "online")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return LyricsResult(isSynced = false, lines = emptyList(), hasLyrics = false)
    }

    private fun queryLrcLib(urlString: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AuraMusicPlayer/2.0")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun queryLrcLibArray(urlString: String): JSONArray? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "AuraMusicPlayer/2.0")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONArray(body)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun parseLrc(content: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        content.lines().forEach { line ->
            val match = timeRegex.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3]
                val ms = when {
                    msStr.length == 2 -> (msStr.toLongOrNull() ?: 0L) * 10
                    msStr.length == 3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (min * 60 * 1000) + (sec * 1000) + ms
                val text = match.groupValues[4].trim()
                if (text.isNotEmpty()) {
                    lines.add(LyricLine(totalMs, text))
                }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun getDemoLyrics(songId: Long): List<LyricLine> {
        return listOf(
            LyricLine(0L, "♪ (Introducción instrumental) ♪"),
            LyricLine(5000L, "Feeling the rhythm in the dark"),
            LyricLine(12000L, "Another neon light ignites the spark"),
            LyricLine(18000L, "Walking through the soundwaves of the night"),
            LyricLine(25000L, "Everything is moving, everything is bright"),
            LyricLine(34000L, "Cause when the music takes control"),
            LyricLine(42000L, "Aura touches deep inside your soul"),
            LyricLine(55000L, "♪ (Solo melódico) ♪"),
            LyricLine(70000L, "Never looking back again"),
            LyricLine(85000L, "Forever lost in rhythm till the end")
        )
    }
}
