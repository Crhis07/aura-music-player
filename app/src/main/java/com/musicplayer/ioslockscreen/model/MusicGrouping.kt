package com.musicplayer.ioslockscreen.model

import android.net.Uri

/**
 * Representa un álbum agrupado estilo BlackPlayer
 */
data class Album(
    val name: String,
    val artist: String,
    val albumArtUri: Uri?,
    val songs: List<Song>
) {
    val trackCount: Int get() = songs.size
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
}

/**
 * Representa un artista agrupado estilo BlackPlayer
 */
data class Artist(
    val name: String,
    val songs: List<Song>,
    val albumsCount: Int,
    val artistArtUri: Uri? = null
) {
    val trackCount: Int get() = songs.size
}

/**
 * Representa una carpeta de música física en el almacenamiento del teléfono
 */
data class MusicFolder(
    val name: String,
    val path: String,
    val songs: List<Song>
) {
    val trackCount: Int get() = songs.size
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
}
