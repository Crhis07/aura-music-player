package com.musicplayer.ioslockscreen.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.musicplayer.ioslockscreen.model.Album
import com.musicplayer.ioslockscreen.model.Artist
import com.musicplayer.ioslockscreen.model.MusicFolder
import com.musicplayer.ioslockscreen.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Contenedor pre-calculado de la biblioteca para evitar lag en el hilo principal
 */
data class LibraryData(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val folders: List<MusicFolder>
)

class MusicRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("music_metadata_overrides", Context.MODE_PRIVATE)

    /**
     * Guarda la metadata editada por el usuario (título, artista, álbum, carátula)
     */
    fun saveSongOverride(song: Song) {
        prefs.edit().apply {
            putString("override_${song.id}_title", song.title)
            putString("override_${song.id}_artist", song.artist)
            putString("override_${song.id}_album", song.album)
            if (song.albumArtUri != null) {
                putString("override_${song.id}_art", song.albumArtUri.toString())
            }
            apply()
        }
    }

    fun isFilterWhatsAppEnabled(): Boolean = prefs.getBoolean("filter_whatsapp", true)

    fun setFilterWhatsApp(enabled: Boolean) {
        prefs.edit().putBoolean("filter_whatsapp", enabled).apply()
    }

    fun getMinDurationSec(): Int = prefs.getInt("min_duration_sec", 30)

    fun setMinDurationSec(seconds: Int) {
        prefs.edit().putInt("min_duration_sec", seconds).apply()
    }

    fun incrementPlayCount(songId: Long): Int {
        val key = "play_count_$songId"
        val current = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, current).apply()
        return current
    }

    fun toggleFavorite(songId: Long): Boolean {
        val key = "fav_$songId"
        val current = prefs.getBoolean(key, false)
        val newFav = !current
        prefs.edit().putBoolean(key, newFav).apply()
        return newFav
    }

    /**
     * Carga completa y pre-agrupada de la biblioteca en segundo plano (Dispatchers.IO)
     */
    suspend fun loadFullLibrary(): LibraryData = withContext(Dispatchers.IO) {
        val songs = getLocalSongs()

        val albums = songs.groupBy { it.album.ifBlank { "Álbum Desconocido" } }
            .map { (name, albumSongs) ->
                Album(
                    name = name,
                    artist = albumSongs.firstOrNull()?.artist ?: "Varios Artistas",
                    albumArtUri = albumSongs.firstOrNull { it.albumArtUri != null }?.albumArtUri,
                    songs = albumSongs
                )
            }.sortedBy { it.name }

        val artists = songs.groupBy { it.artist.ifBlank { "Artista Desconocido" } }
            .map { (name, artistSongs) ->
                val distinctAlbums = artistSongs.map { it.album }.distinct().size
                val representativeArt = artistSongs
                    .sortedByDescending { it.playCount }
                    .firstOrNull { it.albumArtUri != null }?.albumArtUri
                    ?: artistSongs.firstOrNull { it.albumArtUri != null }?.albumArtUri

                Artist(
                    name = name,
                    songs = artistSongs,
                    albumsCount = distinctAlbums,
                    artistArtUri = representativeArt
                )
            }.sortedBy { it.name }

        val folders = songs.groupBy { it.folderName }
            .map { (name, folderSongs) ->
                val samplePath = folderSongs.firstOrNull { it.filePath.isNotBlank() }?.filePath
                val folderPath = samplePath?.let {
                    try { File(it).parent ?: "" } catch (e: Exception) { "" }
                } ?: ""
                MusicFolder(
                    name = name,
                    path = folderPath,
                    songs = folderSongs
                )
            }.sortedBy { it.name }

        LibraryData(songs, albums, artists, folders)
    }

    suspend fun getLocalSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songsList = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA
        )

        val filterWhatsApp = prefs.getBoolean("filter_whatsapp", true)
        val minDurationSec = prefs.getInt("min_duration_sec", 30)
        val minDurationMs = minDurationSec * 1000L

        // Habilita todos los formatos de audio: MP3, FLAC, WAV, ALAC (.m4a), AIFF, OGG, OPUS, AAC
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%') " +
                "AND (${MediaStore.Audio.Media.IS_RINGTONE} == 0) " +
                "AND (${MediaStore.Audio.Media.IS_NOTIFICATION} == 0) " +
                "AND (${MediaStore.Audio.Media.IS_ALARM} == 0) " +
                "AND ${MediaStore.Audio.Media.DURATION} >= $minDurationMs"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val displayCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val duration = cursor.getLong(durationCol)
                    if (duration < minDurationMs) {
                        continue
                    }

                    val rawPath = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                    if (filterWhatsApp && rawPath.isNotBlank()) {
                        val lower = rawPath.lowercase()
                        if (lower.contains("whatsapp") ||
                            lower.contains("voice notes") ||
                            lower.contains("/ringtones/") ||
                            lower.contains("/notifications/") ||
                            lower.contains("/alarms/") ||
                            lower.contains("recordings") ||
                            lower.contains(".statuses") ||
                            lower.contains("call_rec")
                        ) {
                            continue
                        }
                    }

                    val id = cursor.getLong(idCol)
                    val displayName = if (displayCol >= 0) cursor.getString(displayCol) else null
                    val cleanFallbackTitle = displayName?.substringBeforeLast(".")?.ifBlank { "Pista de Audio" } ?: "Pista de Audio"
                    val fetchedTitle = cursor.getString(titleCol)
                    val rawTitle = if (!fetchedTitle.isNullOrBlank()) fetchedTitle else cleanFallbackTitle
                    val rawArtist = cursor.getString(artistCol)?.ifBlank { "Artista Desconocido" } ?: "Artista Desconocido"
                    val rawAlbum = cursor.getString(albumCol)?.ifBlank { "Álbum Desconocido" } ?: "Álbum Desconocido"
                    val albumId = cursor.getLong(albumIdCol)
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L

                    val folderName = if (rawPath.isNotBlank()) {
                        try {
                            File(rawPath).parentFile?.name?.ifBlank { "Música" } ?: "Música"
                        } catch (e: Exception) {
                            "Música"
                        }
                    } else "Música"

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    val rawAlbumArtUri = if (albumId > 0) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        )
                    } else null

                    // Aplicar sobreescrituras guardadas por el usuario (si existen)
                    val savedTitle = prefs.getString("override_${id}_title", null) ?: rawTitle
                    val savedArtist = prefs.getString("override_${id}_artist", null) ?: rawArtist
                    val savedAlbum = prefs.getString("override_${id}_album", null) ?: rawAlbum
                    val savedArt = prefs.getString("override_${id}_art", null)
                    val finalArtUri = if (!savedArt.isNullOrBlank()) Uri.parse(savedArt) else rawAlbumArtUri

                    val playCount = prefs.getInt("play_count_$id", 0)
                    val isFav = prefs.getBoolean("fav_$id", false)

                    songsList.add(
                        Song(
                            id = id,
                            title = savedTitle,
                            artist = savedArtist,
                            album = savedAlbum,
                            durationMs = duration,
                            mediaUri = contentUri,
                            albumArtUri = finalArtUri,
                            isDemo = false,
                            dateAdded = dateAdded,
                            playCount = playCount,
                            isFavorite = isFav,
                            folderName = folderName,
                            filePath = rawPath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Si no hay canciones locales en el teléfono, agregamos pistas de demostración
        if (songsList.isEmpty()) {
            songsList.addAll(getDemoSongs())
        }

        return@withContext songsList
    }

    fun getDemoSongs(): List<Song> {
        return listOf(
            Song(
                id = 1001L,
                title = "Out of Ordinary",
                artist = "Oliver Tree",
                album = "Out of Ordinary - Single",
                durationMs = 184000L,
                mediaUri = Uri.parse("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
                albumArtUri = Uri.parse("https://picsum.photos/id/1062/600/600"),
                isDemo = true,
                dateAdded = System.currentTimeMillis() / 1000 - 86400,
                playCount = 18,
                isFavorite = true
            ),
            Song(
                id = 1002L,
                title = "Midnight City Lights",
                artist = "Synthwave Collective",
                album = "Neon Horizon",
                durationMs = 212000L,
                mediaUri = Uri.parse("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
                albumArtUri = Uri.parse("https://picsum.photos/id/1049/600/600"),
                isDemo = true,
                dateAdded = System.currentTimeMillis() / 1000 - 3600,
                playCount = 9,
                isFavorite = false
            ),
            Song(
                id = 1003L,
                title = "Sunset Dreamer",
                artist = "Acoustic Sunset",
                album = "Golden Hour Sessions",
                durationMs = 195000L,
                mediaUri = Uri.parse("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"),
                albumArtUri = Uri.parse("https://picsum.photos/id/1050/600/600"),
                isDemo = true,
                dateAdded = System.currentTimeMillis() / 1000 - 172800,
                playCount = 3,
                isFavorite = false
            )
        )
    }
}
