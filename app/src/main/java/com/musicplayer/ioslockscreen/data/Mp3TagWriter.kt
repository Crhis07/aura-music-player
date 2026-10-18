package com.musicplayer.ioslockscreen.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mpatric.mp3agic.ID3v2
import com.mpatric.mp3agic.ID3v23Tag
import com.mpatric.mp3agic.Mp3File
import com.musicplayer.ioslockscreen.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class BatchSyncResult(
    val totalProcessed: Int,
    val successCount: Int,
    val failedCount: Int,
    val skippedAlreadySyncedCount: Int = 0,
    val skippedNonMp3Count: Int = 0
)

object Mp3TagWriter {

    /**
     * Verifica si la app tiene permiso para modificar archivos en el almacenamiento
     */
    fun canWriteMp3Files(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Lanza la pantalla de ajustes del sistema para conceder el permiso de acceso a archivos
     */
    fun requestStorageManagementPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Graba directamente en el archivo binario MP3 las etiquetas ID3v2.3 y la imagen de carátula
     */
    suspend fun writeTagsToMp3(
        context: Context,
        song: Song,
        title: String,
        artist: String,
        album: String,
        artworkUri: Uri?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (song.filePath.isBlank()) {
                return@withContext Result.failure(Exception("La canción no tiene una ruta de archivo local"))
            }

            val originalFile = File(song.filePath)
            if (!originalFile.exists() || !originalFile.isFile) {
                return@withContext Result.failure(Exception("El archivo físico no existe en ${song.filePath}"))
            }

            if (!originalFile.name.endsWith(".mp3", ignoreCase = true)) {
                return@withContext Result.failure(Exception("Solo se soporta incrustación en archivos con formato .mp3"))
            }

            // 1. Obtener y comprimir la carátula en tamaño estándar optimizado (máx 800x800)
            val artBytesAndMime = artworkUri?.let { extractOptimizedArtworkBytes(context, it) }

            // 2. Cargar el MP3 con mp3agic
            val mp3file = Mp3File(originalFile.absolutePath)

            // Usamos ID3v2.3 por ser el estándar más universal en Android, Windows, Mac y reproductores de auto
            val id3Tag: ID3v2 = if (mp3file.hasId3v2Tag()) {
                mp3file.id3v2Tag
            } else {
                val newTag = ID3v23Tag()
                mp3file.id3v2Tag = newTag
                newTag
            }

            id3Tag.title = title.trim()
            id3Tag.artist = artist.trim()
            id3Tag.album = album.trim()

            if (artBytesAndMime != null) {
                id3Tag.setAlbumImage(artBytesAndMime.first, artBytesAndMime.second)
            }

            // 3. Guardar primero en un archivo temporal seguro en cacheDir
            val tempFile = File(context.cacheDir, "id3_temp_${System.currentTimeMillis()}.mp3")
            try {
                mp3file.save(tempFile.absolutePath)

                if (!tempFile.exists() || tempFile.length() <= 0L) {
                    return@withContext Result.failure(Exception("No se pudo generar el archivo temporal con las etiquetas"))
                }

                // 4. Sobrescribir el archivo original
                tempFile.copyTo(originalFile, overwrite = true)

                // 5. Notificar a Android MediaStore para refrescar el sistema operativo
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(originalFile.absolutePath),
                    arrayOf("audio/mpeg"),
                    null
                )

                // 6. Registrar en caché de sincronización para no re-procesar innecesariamente
                markSongAsSynced(
                    context = context,
                    songId = song.id,
                    lastModified = originalFile.lastModified(),
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUri = artworkUri
                )

                Result.success(originalFile)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private const val SYNC_PREFS_NAME = "mp3_synced_tags_prefs"

    fun markSongAsSynced(
        context: Context,
        songId: Long,
        lastModified: Long,
        title: String,
        artist: String,
        album: String,
        artworkUri: Uri?
    ) {
        try {
            val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            val signature = generateSignature(title, artist, album, artworkUri, lastModified)
            prefs.edit().putString("sync_sig_$songId", signature).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isSongAlreadySynced(
        context: Context,
        song: Song,
        originalFile: File
    ): Boolean {
        if (!originalFile.exists()) return false
        val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        val savedSig = prefs.getString("sync_sig_${song.id}", null)
        val currentSig = generateSignature(song.title, song.artist, song.album, song.albumArtUri, originalFile.lastModified())
        return savedSig != null && savedSig == currentSig
    }

    /**
     * Comprueba directamente si el archivo MP3 físico ya contiene los mismos metadatos y carátula
     */
    fun isPhysicalMp3AlreadyTagged(song: Song, originalFile: File): Boolean {
        return try {
            val mp3file = Mp3File(originalFile.absolutePath)
            if (!mp3file.hasId3v2Tag()) return false
            val tag = mp3file.id3v2Tag
            val titleMatch = (tag.title ?: "").trim().equals(song.title.trim(), ignoreCase = true)
            val artistMatch = (tag.artist ?: "").trim().equals(song.artist.trim(), ignoreCase = true)
            val albumMatch = (tag.album ?: "").trim().equals(song.album.trim(), ignoreCase = true)
            val artMatch = if (song.albumArtUri != null) {
                tag.albumImage != null && tag.albumImage.isNotEmpty()
            } else {
                true
            }
            titleMatch && artistMatch && albumMatch && artMatch
        } catch (_: Exception) {
            false
        }
    }

    private fun generateSignature(
        title: String,
        artist: String,
        album: String,
        artworkUri: Uri?,
        lastModified: Long
    ): String {
        return "${title.trim()}|${artist.trim()}|${album.trim()}|${artworkUri?.toString() ?: ""}|$lastModified"
    }

    /**
     * Sincroniza en lote todas las canciones existentes de la biblioteca grabando sus metadatos y carátulas.
     * Solo procesa canciones nuevas o con datos modificados, saltándose las que ya están al día.
     */
    suspend fun batchSyncAllSongs(
        context: Context,
        songs: List<Song>,
        forceResyncAll: Boolean = false,
        onProgress: (current: Int, total: Int, songTitle: String) -> Unit
    ): BatchSyncResult = withContext(Dispatchers.IO) {
        var success = 0
        var failed = 0
        var alreadySynced = 0

        val eligibleSongs = songs.filter { it.filePath.endsWith(".mp3", ignoreCase = true) }
        val nonMp3Count = songs.size - eligibleSongs.size

        val songsToProcess = if (forceResyncAll) {
            eligibleSongs
        } else {
            val pending = mutableListOf<Song>()
            for (song in eligibleSongs) {
                val file = File(song.filePath)
                if (!file.exists()) {
                    failed++
                    continue
                }
                if (isSongAlreadySynced(context, song, file)) {
                    alreadySynced++
                } else if (isPhysicalMp3AlreadyTagged(song, file)) {
                    // Recordar en preferencias para que futuras comprobaciones sean ultra-rápidas
                    markSongAsSynced(
                        context,
                        song.id,
                        file.lastModified(),
                        song.title,
                        song.artist,
                        song.album,
                        song.albumArtUri
                    )
                    alreadySynced++
                } else {
                    pending.add(song)
                }
            }
            pending
        }

        val total = songsToProcess.size

        if (total == 0) {
            return@withContext BatchSyncResult(
                totalProcessed = 0,
                successCount = 0,
                failedCount = failed,
                skippedAlreadySyncedCount = alreadySynced,
                skippedNonMp3Count = nonMp3Count
            )
        }

        songsToProcess.forEachIndexed { index, song ->
            withContext(Dispatchers.Main) {
                onProgress(index + 1, total, song.title)
            }

            val result = writeTagsToMp3(
                context = context,
                song = song,
                title = song.title,
                artist = song.artist,
                album = song.album,
                artworkUri = song.albumArtUri
            )

            if (result.isSuccess) {
                success++
            } else {
                failed++
            }
        }

        BatchSyncResult(
            totalProcessed = total,
            successCount = success,
            failedCount = failed,
            skippedAlreadySyncedCount = alreadySynced,
            skippedNonMp3Count = nonMp3Count
        )
    }

    private fun extractOptimizedArtworkBytes(context: Context, uri: Uri): Pair<ByteArray, String>? {
        return try {
            val rawBytes = if (uri.toString().startsWith("http://") || uri.toString().startsWith("https://")) {
                val conn = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.inputStream.use { it.readBytes() }
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return null

            // Decodificar dimensiones para evitar incrustar imágenes gigantescas
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

            val maxDimension = 800
            val largest = maxOf(options.outWidth, options.outHeight)
            var inSampleSize = 1
            if (largest > maxDimension) {
                inSampleSize = largest / maxDimension
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }

            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

            val outStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
            val optimizedBytes = outStream.toByteArray()

            Pair(optimizedBytes, "image/jpeg")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
