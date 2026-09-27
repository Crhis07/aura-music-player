package com.musicplayer.ioslockscreen.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mediaUri: Uri,
    val albumArtUri: Uri? = null,
    val isDemo: Boolean = false,
    val dateAdded: Long = 0L,
    val playCount: Int = 0,
    val isFavorite: Boolean = false,
    val folderName: String = "Música",
    val filePath: String = ""
) {
    fun getFormattedDuration(): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val remainingSeconds = totalSeconds % 60
        return "%d:%02d".format(minutes, remainingSeconds)
    }

    companion object {
        fun formatMs(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val remainingSeconds = totalSeconds % 60
            return "%d:%02d".format(minutes, remainingSeconds)
        }

        fun formatRemainingMs(currentMs: Long, totalMs: Long): String {
            val remaining = (totalMs - currentMs).coerceAtLeast(0)
            val totalSeconds = remaining / 1000
            val minutes = totalSeconds / 60
            val remainingSeconds = totalSeconds % 60
            return "-%d:%02d".format(minutes, remainingSeconds)
        }
    }
}

fun Song.toMediaItem(): androidx.media3.common.MediaItem {
    val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(albumArtUri)
        .build()

    val builder = androidx.media3.common.MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(mediaUri)
        .setMediaMetadata(metadata)
        .setRequestMetadata(
            androidx.media3.common.MediaItem.RequestMetadata.Builder()
                .setMediaUri(mediaUri)
                .build()
        )

    return builder.build()
}
