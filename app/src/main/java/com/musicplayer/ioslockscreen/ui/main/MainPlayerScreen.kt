package com.musicplayer.ioslockscreen.ui.main

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.musicplayer.ioslockscreen.R
import com.musicplayer.ioslockscreen.data.AppUpdateInfo
import com.musicplayer.ioslockscreen.data.AppUpdateManager
import com.musicplayer.ioslockscreen.data.LyricsHelper
import com.musicplayer.ioslockscreen.data.MusicMetadataSearchService
import com.musicplayer.ioslockscreen.data.MusicRepository
import com.musicplayer.ioslockscreen.model.Album
import com.musicplayer.ioslockscreen.model.Artist
import com.musicplayer.ioslockscreen.model.MusicFolder
import com.musicplayer.ioslockscreen.model.Song
import android.widget.Toast
import kotlinx.coroutines.launch
import com.musicplayer.ioslockscreen.service.MusicPlaybackService
import com.musicplayer.ioslockscreen.service.SleepTimerManager
import com.musicplayer.ioslockscreen.ui.lockscreen.LockScreenActivity

// Caché en memoria para URIs sin carátula: evita reintentar I/O fallidos en MediaStore
private val failedArtworkUris = java.util.Collections.synchronizedSet(mutableSetOf<Uri>())

enum class LibraryTab(val title: String, val icon: ImageVector) {
    TRACKS("Pistas", Icons.Default.MusicNote),
    ALBUMS("Álbumes", Icons.Default.Album),
    ARTISTS("Artistas", Icons.Default.Person),
    FOLDERS("Carpetas", Icons.Default.Folder),
    SETTINGS("Ajustes", Icons.Default.Settings)
}

enum class AlbumViewMode {
    GRID_COMPACT, // 3 columnas (compacto y ultra nítido estilo Apple Music)
    GRID_LARGE,   // 2 columnas (grande)
    LIST          // Lista detallada (estilo BlackPlayer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPlayerScreen(
    songs: List<Song>,
    albums: List<Album>,
    artists: List<Artist>,
    folders: List<MusicFolder> = emptyList(),
    currentSong: Song?,
    isPlaying: Boolean,
    repeatMode: Int = 2,
    isShuffleEnabled: Boolean = false,
    currentPositionMs: Long = 0L,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSongSelected: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit = {},
    onUpdateSongMetadata: (Song) -> Unit,
    onSeekTo: (Long) -> Unit = {},
    onReloadLibrary: () -> Unit = {},
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onPlayPlaylist: (List<Song>, Boolean) -> Unit = { _, _ -> },
    onTriggerUpdateDialog: (AppUpdateInfo) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(LibraryTab.TRACKS) }
    var searchQuery by remember { mutableStateOf("") }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showExpandedPlayer by remember { mutableStateOf(false) }
    var selectedAlbumForDetail by remember { mutableStateOf<Album?>(null) }
    var selectedArtistForDetail by remember { mutableStateOf<Artist?>(null) }
    var selectedFolderForDetail by remember { mutableStateOf<MusicFolder?>(null) }
    var songToEdit by remember { mutableStateOf<Song?>(null) }
    var isLockEnabled by remember { mutableStateOf(MusicPlaybackService.isLockScreenEnabled) }

    val remainingSleepSec by SleepTimerManager.remainingSeconds.collectAsState()

    // 1. Filtrado ágil de canciones
    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 2. Filtrado ágil de Álbumes (ya calculados en segundo plano)
    val filteredAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) albums
        else albums.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    // 3. Filtrado ágil de Artistas (ya calculados en segundo plano)
    val filteredArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) artists
        else artists.filter {
            it.name.contains(searchQuery, ignoreCase = true)
        }
    }

    // 4. Filtrado ágil de Carpetas
    val filteredFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.path.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Aura Music",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp
                        )
                        Text(
                            text = when (selectedTab) {
                                LibraryTab.TRACKS -> "${songs.size} canciones"
                                LibraryTab.ALBUMS -> "${albums.size} álbumes"
                                LibraryTab.ARTISTS -> "${artists.size} artistas"
                                LibraryTab.FOLDERS -> "${folders.size} carpetas"
                                LibraryTab.SETTINGS -> "Configuración & Opciones"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Badge de temporizador si está activo
                    if (remainingSleepSec != null && remainingSleepSec!! > 0) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clickable { showSleepTimerDialog = true }
                                .padding(end = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HourglassBottom,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = SleepTimerManager.formatRemaining(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Botón para probar pantalla de bloqueo
                    IconButton(
                        onClick = {
                            val intent = Intent(context, LockScreenActivity::class.java)
                            context.startActivity(intent)
                        }
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = "Ver Pantalla de Bloqueo",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            ) {
                // Mini reproductor flotante estilo Apple Music
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    FloatingMiniPlayer(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        repeatMode = repeatMode,
                        isShuffleEnabled = isShuffleEnabled,
                        currentPositionMs = currentPositionMs,
                        onPlayPauseToggle = onPlayPauseToggle,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onToggleRepeat = onToggleRepeat,
                        onToggleShuffle = onToggleShuffle,
                        onClick = {
                            showExpandedPlayer = true
                        }
                    )
                }

                // Barra de navegación inferior con WindowInsets naturales (sin corte)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    windowInsets = NavigationBarDefaults.windowInsets
                ) {
                    LibraryTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                LibraryTab.TRACKS -> {
                    TracksTabContent(
                        songs = filteredSongs,
                        currentSong = currentSong,
                        hasPermission = hasPermission,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onRequestPermission = onRequestPermission,
                        onSongSelected = onSongSelected,
                        onPlayAll = onPlayPlaylist,
                        onEditSong = { songToEdit = it },
                        onToggleFavorite = onToggleFavorite
                    )
                }

                LibraryTab.ALBUMS -> {
                    AlbumsTabContent(
                        albums = filteredAlbums,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onAlbumClick = { album -> selectedAlbumForDetail = album }
                    )
                }

                LibraryTab.ARTISTS -> {
                    ArtistsTabContent(
                        artists = filteredArtists,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onArtistClick = { artist -> selectedArtistForDetail = artist }
                    )
                }

                LibraryTab.FOLDERS -> {
                    FoldersTabContent(
                        folders = filteredFolders,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onFolderClick = { folder -> selectedFolderForDetail = folder }
                    )
                }

                LibraryTab.SETTINGS -> {
                    SettingsTabContent(
                        isLockEnabled = isLockEnabled,
                        onLockEnabledChange = {
                            isLockEnabled = it
                            MusicPlaybackService.isLockScreenEnabled = it
                        },
                        remainingSleepSec = remainingSleepSec,
                        repeatMode = repeatMode,
                        isShuffleEnabled = isShuffleEnabled,
                        onToggleRepeat = onToggleRepeat,
                        onToggleShuffle = onToggleShuffle,
                        onOpenSleepTimerDialog = { showSleepTimerDialog = true },
                        onCancelSleepTimer = { SleepTimerManager.cancelTimer() },
                        onReloadLibrary = onReloadLibrary,
                        onTestLockScreen = {
                            val intent = Intent(context, LockScreenActivity::class.java)
                            context.startActivity(intent)
                        },
                        onTriggerUpdateDialog = onTriggerUpdateDialog
                    )
                }
            }
        }
    }

    // Modal para Editar Información / Metadatos con búsqueda online
    songToEdit?.let { song ->
        SongMetadataEditSheet(
            song = song,
            onDismissRequest = { songToEdit = null },
            onSaveMetadata = { updatedSong ->
                onUpdateSongMetadata(updatedSong)
                songToEdit = null
            }
        )
    }

    // Diálogo del Temporizador de Apagado
    if (showSleepTimerDialog) {
        SleepTimerDialog(
            onDismissRequest = { showSleepTimerDialog = false }
        )
    }

    // BottomSheet del Reproductor Expandido con Letras estilo Spotify
    if (showExpandedPlayer && currentSong != null) {
        ExpandedPlayerBottomSheet(
            currentSong = currentSong,
            isPlaying = isPlaying,
            repeatMode = repeatMode,
            isShuffleEnabled = isShuffleEnabled,
            currentPositionMs = currentPositionMs,
            onSeekTo = onSeekTo,
            onPlayPauseToggle = onPlayPauseToggle,
            onNext = onNext,
            onPrevious = onPrevious,
            onToggleRepeat = onToggleRepeat,
            onToggleShuffle = onToggleShuffle,
            onToggleFavorite = onToggleFavorite,
            onDismissRequest = { showExpandedPlayer = false }
        )
    }

    // BottomSheet con canciones de la carpeta seleccionada
    selectedFolderForDetail?.let { folder ->
        FolderDetailBottomSheet(
            folder = folder,
            currentSong = currentSong,
            onSongSelected = { song ->
                onSongSelected(song)
                selectedFolderForDetail = null
            },
            onPlayPlaylist = { songList, shuffle ->
                onPlayPlaylist(songList, shuffle)
                selectedFolderForDetail = null
            },
            onDismissRequest = { selectedFolderForDetail = null }
        )
    }

    // BottomSheet con pistas del álbum seleccionado
    selectedAlbumForDetail?.let { album ->
        AlbumDetailBottomSheet(
            album = album,
            currentSong = currentSong,
            onSongSelected = { song ->
                onSongSelected(song)
                selectedAlbumForDetail = null
            },
            onPlayPlaylist = { songList, shuffle ->
                onPlayPlaylist(songList, shuffle)
                selectedAlbumForDetail = null
            },
            onDismissRequest = { selectedAlbumForDetail = null }
        )
    }

    // BottomSheet con canciones del artista seleccionado
    selectedArtistForDetail?.let { artist ->
        val artistAlbum = Album(
            name = "Discografía de ${artist.name}",
            artist = artist.name,
            albumArtUri = artist.songs.firstOrNull { it.albumArtUri != null }?.albumArtUri,
            songs = artist.songs
        )
        AlbumDetailBottomSheet(
            album = artistAlbum,
            currentSong = currentSong,
            onSongSelected = { song ->
                onSongSelected(song)
                selectedArtistForDetail = null
            },
            onPlayPlaylist = { songList, shuffle ->
                onPlayPlaylist(songList, shuffle)
                selectedArtistForDetail = null
            },
            onDismissRequest = { selectedArtistForDetail = null }
        )
    }
}

/**
 * Contenido de la pestaña Canciones (Optimizada para 60/120 FPS sin lag)
 */
enum class TracksSubFilter(val title: String, val icon: ImageVector) {
    ALL("Todas", Icons.Default.QueueMusic),
    MOST_PLAYED("Más Escuchadas", Icons.Default.LocalFireDepartment),
    RECENTLY_ADDED("Agregadas Recientemente", Icons.Default.Schedule),
    FAVORITES("Favoritas", Icons.Default.Favorite)
}

/**
 * Contenido de la pestaña Canciones con sub-vistas: Todas, Más Escuchadas, Agregadas Recientemente, Favoritas
 */
@Composable
fun TracksTabContent(
    songs: List<Song>,
    currentSong: Song?,
    hasPermission: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onSongSelected: (Song) -> Unit,
    onPlayAll: (List<Song>, Boolean) -> Unit = { _, _ -> },
    onEditSong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit
) {
    var activeSubFilter by remember { mutableStateOf(TracksSubFilter.ALL) }

    // Ordenamiento y filtrado reactivo según el sub-filtro seleccionado
    val displayedSongs = remember(songs, searchQuery, activeSubFilter) {
        val base = if (searchQuery.isBlank()) {
            songs
        } else {
            songs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
            }
        }

        when (activeSubFilter) {
            TracksSubFilter.ALL -> base.sortedBy { it.title.lowercase() }
            TracksSubFilter.MOST_PLAYED -> base.sortedByDescending { it.playCount }
            TracksSubFilter.RECENTLY_ADDED -> base.sortedByDescending { it.dateAdded }
            TracksSubFilter.FAVORITES -> base.filter { it.isFavorite }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Barra de búsqueda
        item(key = "search_field") {
            SearchBarField(
                query = searchQuery,
                onQueryChange = onSearchChange,
                placeholder = "Buscar canciones, artistas..."
            )
        }

        // 2. Barra de sub-filtros moderna con píldoras de desplazamiento horizontal
        item(key = "sub_filters_row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TracksSubFilter.values().forEach { filter ->
                    val isSelected = activeSubFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeSubFilter = filter },
                        label = {
                            Text(
                                text = filter.title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = filter.icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(
                            width = 0.5.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        }

        // Permisos si faltan
        if (!hasPermission) {
            item(key = "permission_notice") {
                PermissionRequiredCard(onRequestPermission = onRequestPermission)
            }
        }

        // 3. Encabezado dinámico según la vista activa
        item(key = "tracks_header") {
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (activeSubFilter) {
                            TracksSubFilter.ALL -> "Todas las canciones (${displayedSongs.size})"
                            TracksSubFilter.MOST_PLAYED -> "Ranking: Más Escuchadas (${displayedSongs.size})"
                            TracksSubFilter.RECENTLY_ADDED -> "Agregadas Recientemente (${displayedSongs.size})"
                            TracksSubFilter.FAVORITES -> "Tus Favoritas (${displayedSongs.size})"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (activeSubFilter == TracksSubFilter.MOST_PLAYED) {
                        Text(
                            text = "Por reproducciones",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = when (activeSubFilter) {
                        TracksSubFilter.ALL -> "Biblioteca completa por orden alfabético"
                        TracksSubFilter.MOST_PLAYED -> "Tus pistas más reproducidas en el dispositivo"
                        TracksSubFilter.RECENTLY_ADDED -> "Tus últimas canciones añadidas al teléfono"
                        TracksSubFilter.FAVORITES -> "Tus canciones favoritas marcadas con corazón"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (displayedSongs.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onPlayAll(displayedSongs, false) },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reproducir", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        FilledTonalButton(
                            onClick = { onPlayAll(displayedSongs, true) },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Aleatorio", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Mensaje si no hay favoritas aún
        if (displayedSongs.isEmpty() && activeSubFilter == TracksSubFilter.FAVORITES) {
            item(key = "empty_favorites") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Sin canciones favoritas",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Toca el corazón en cualquier canción para guardarla aquí.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // 4. Lista de canciones ultra-rápida a 60/120 FPS con ranking posicional
        itemsIndexed(
            items = displayedSongs,
            key = { _, song -> song.id },
            contentType = { _, _ -> "track_row" }
        ) { index, song ->
            val isSelected = currentSong?.id == song.id
            SongRowItem(
                song = song,
                isSelected = isSelected,
                rankingIndex = if (activeSubFilter == TracksSubFilter.MOST_PLAYED) index + 1 else null,
                showRecentlyAddedTag = activeSubFilter == TracksSubFilter.RECENTLY_ADDED,
                onClick = { onSongSelected(song) },
                onEditClick = { onEditSong(song) },
                onToggleFavorite = { onToggleFavorite(song) }
            )
        }

        item(key = "spacer_bottom") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Contenido de la pestaña Álbumes con selector de visualización (3 col compacto, 2 col grande, lista)
 */
@Composable
fun AlbumsTabContent(
    albums: List<Album>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    var viewMode by remember { mutableStateOf(AlbumViewMode.GRID_COMPACT) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SearchBarField(
            query = searchQuery,
            onQueryChange = onSearchChange,
            placeholder = "Buscar álbumes o artistas..."
        )

        // Barra de control de visualización (Modo 3 columnas, 2 columnas o lista)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${albums.size} álbumes",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewMode = AlbumViewMode.GRID_COMPACT },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = "3 columnas (compacto y nítido)",
                        tint = if (viewMode == AlbumViewMode.GRID_COMPACT)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { viewMode = AlbumViewMode.GRID_LARGE },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "2 columnas (grande)",
                        tint = if (viewMode == AlbumViewMode.GRID_LARGE)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { viewMode = AlbumViewMode.LIST },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewList,
                        contentDescription = "Lista detallada",
                        tint = if (viewMode == AlbumViewMode.LIST)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        when (viewMode) {
            // MODO 1: 3 COLUMNAS (COMPACTO, EVITA PIXELADO EN IMÁGENES DE 300x300)
            AlbumViewMode.GRID_COMPACT -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = albums,
                        key = { it.name + it.artist },
                        contentType = { "album_grid_compact" }
                    ) { album ->
                        AlbumCompactCard(
                            album = album,
                            onClick = { onAlbumClick(album) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // MODO 2: 2 COLUMNAS (GRANDE)
            AlbumViewMode.GRID_LARGE -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = albums,
                        key = { it.name + it.artist },
                        contentType = { "album_grid_card" }
                    ) { album ->
                        AlbumGridCard(
                            album = album,
                            onClick = { onAlbumClick(album) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // MODO 3: LISTA DETALLADA
            AlbumViewMode.LIST -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = albums,
                        key = { it.name + it.artist },
                        contentType = { "album_list_row" }
                    ) { album ->
                        AlbumListRow(
                            album = album,
                            onClick = { onAlbumClick(album) }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta de Álbum en Cuadrícula Compacta (3 columnas)
 * Al ser más compacta (~110dp), imágenes de 300x300 o 600x600 se ven 100% nítidas sin pixelado
 */
@Composable
fun AlbumCompactCard(
    album: Album,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(6.dp)
        ) {
            OptimizedSongThumbnail(
                artUri = album.albumArtUri,
                targetSize = 350,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = album.name,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${album.trackCount} canc.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Tarjeta de Álbum en Cuadrícula Grande (2 columnas) con decodificación de alta fidelidad
 */
@Composable
fun AlbumGridCard(
    album: Album,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            OptimizedSongThumbnail(
                artUri = album.albumArtUri,
                targetSize = 500,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = album.name,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${album.trackCount} canciones",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Fila de Álbum en Vista de Lista estilo BlackPlayer
 */
@Composable
fun AlbumListRow(
    album: Album,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptimizedSongThumbnail(
                artUri = album.albumArtUri,
                targetSize = 200,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(50.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.artist} • ${album.trackCount} canciones",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Contenido de la pestaña Artistas estilo BlackPlayer
 */
@Composable
fun ArtistsTabContent(
    artists: List<Artist>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onArtistClick: (Artist) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "artist_search") {
            SearchBarField(
                query = searchQuery,
                onQueryChange = onSearchChange,
                placeholder = "Buscar artistas..."
            )
        }

        items(
            items = artists,
            key = { it.name },
            contentType = { "artist_row" }
        ) { artist ->
            ArtistRowItem(
                artist = artist,
                onClick = { onArtistClick(artist) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Fila de Artista estilo BlackPlayer
 */
@Composable
fun ArtistRowItem(
    artist: Artist,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (artist.artistArtUri != null) {
                OptimizedSongThumbnail(
                    artUri = artist.artistArtUri,
                    targetSize = 180,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val initial = artist.name.trim().take(1).uppercase()
                        if (initial.isNotBlank() && initial[0].isLetterOrDigit()) {
                            Text(
                                text = initial,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${artist.trackCount} canciones • ${artist.albumsCount} álbumes",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Contenido de la pestaña Carpetas (Explorador de música física)
 */
@Composable
fun FoldersTabContent(
    folders: List<MusicFolder>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onFolderClick: (MusicFolder) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "folder_search") {
            SearchBarField(
                query = searchQuery,
                onQueryChange = onSearchChange,
                placeholder = "Buscar carpetas..."
            )
        }

        item(key = "folder_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${folders.size} carpetas de audio",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (folders.isEmpty()) {
            item(key = "empty_folders") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No se encontraron carpetas con música",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(
            items = folders,
            key = { "folder_${it.name}_${it.path}" }
        ) { folder ->
            Surface(
                onClick = { onFolderClick(folder) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (folder.path.isNotBlank()) {
                            Text(
                                text = folder.path,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                        Text(
                            text = "${folder.trackCount} canciones • ${Song.formatMs(folder.totalDurationMs)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Abrir carpeta",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Contenido de la pestaña Ajustes (Temporizador de apagado, Bloqueo iOS, Filtros y Bluetooth)
 */
@Composable
fun SettingsTabContent(
    isLockEnabled: Boolean,
    onLockEnabledChange: (Boolean) -> Unit,
    remainingSleepSec: Long?,
    repeatMode: Int = 2,
    isShuffleEnabled: Boolean = false,
    onToggleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onOpenSleepTimerDialog: () -> Unit,
    onCancelSleepTimer: () -> Unit,
    onReloadLibrary: () -> Unit = {},
    onTestLockScreen: () -> Unit,
    onTriggerUpdateDialog: (AppUpdateInfo) -> Unit = {}
) {
    val context = LocalContext.current
    val currentVersionName = remember { AppUpdateManager.getCurrentVersionName(context) }
    val currentVersionCode = remember { AppUpdateManager.getCurrentVersionCode(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckStatus by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // SECCIÓN: MODOS DE REPRODUCCIÓN (REPETIR Y ALEATORIO)
        item {
            Text(
                text = "Modos de Reproducción",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Modo Repetición
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            val repeatIcon = if (repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat
                            Icon(
                                imageVector = repeatIcon,
                                contentDescription = null,
                                tint = if (repeatMode != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Modo de Repetición",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = when (repeatMode) {
                                        0 -> "Desactivado (Detener al final)"
                                        1 -> "Repetir 1 canción en bucle"
                                        else -> "Repetir lista completa"
                                    },
                                    fontSize = 12.sp,
                                    color = if (repeatMode != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onToggleRepeat,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = when (repeatMode) {
                                    0 -> "Apagado"
                                    1 -> "Una pista"
                                    else -> "Toda la lista"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Modo Aleatorio (Shuffle)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = null,
                                tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Reproducción Aleatoria (Shuffle)",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (isShuffleEnabled) "Activado (Orden aleatorio)" else "Desactivado (Orden secuencial)",
                                    fontSize = 12.sp,
                                    color = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isShuffleEnabled,
                            onCheckedChange = { onToggleShuffle() }
                        )
                    }
                }
            }
        }

        // SECCIÓN: PANTALLA DE BLOQUEO ESTILO IOS 16
        item {
            Text(
                text = "Pantalla de Bloqueo Estilo iOS 16",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            val context = LocalContext.current
            var isLockScreenEnabled by remember {
                mutableStateOf(MusicPlaybackService.isLockScreenEnabled(context))
            }
            var hasOverlayPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
                )
            }

            // Actualizar estado del permiso al volver a la app
            DisposableEffect(Unit) {
                hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
                onDispose {}
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Switch de activación
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Pantalla de Bloqueo Automática",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (isLockScreenEnabled)
                                        "Se muestra automáticamente al apagar/bloquear el celular."
                                    else
                                        "Desactivada (no se abrirá al bloquear).",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isLockScreenEnabled,
                            onCheckedChange = {
                                isLockScreenEnabled = it
                                MusicPlaybackService.setLockScreenEnabled(context, it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Estado del permiso de superposición (Aparecer encima)
                    if (hasOverlayPermission) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF34C759).copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF34C759),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Permiso del sistema concedido. La pantalla de bloqueo funcionará en automático.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Acción necesaria para modo automático",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Text(
                                    text = "Android exige el permiso 'Aparecer por encima' para permitir que el reproductor se abra automáticamente al bloquear tu teléfono.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Activar permiso 'Aparecer encima'")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Botón para probar la pantalla de bloqueo de inmediato
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(context, LockScreenActivity::class.java)
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Probar pantalla de bloqueo ahora")
                    }
                }
            }
        }

        // SECCIÓN: CONTROL DE DATOS Y CONECTIVIDAD
        item {
            Text(
                text = "Consumo de Datos e Internet",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            val context = LocalContext.current
            var isWifiOnly by remember { mutableStateOf(MusicMetadataSearchService.isWifiOnly(context)) }
            var isAllSearchDisabled by remember { mutableStateOf(MusicMetadataSearchService.isOnlineSearchDisabled(context)) }
            var isAutoLyricsDownload by remember { mutableStateOf(LyricsHelper.isAutoLyricsDownloadEnabled(context)) }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Badge Informativo 100% Offline
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Reproductor 100% Offline",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Tus canciones y carátulas se leen del almacenamiento de tu teléfono. La app NO consume datos móviles al reproducir.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Opción 1: Bloquear datos móviles (Solo Wi-Fi)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Solo Wi-Fi para carátulas y letras",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Bloquea cualquier descarga si estás usando tu paquete de datos móviles.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isWifiOnly,
                            onCheckedChange = {
                                isWifiOnly = it
                                MusicMetadataSearchService.setWifiOnly(context, it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Opción 2: Descargar letras automáticamente
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Descargar letras automáticamente",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Busca la letra en LRCLIB al reproducir una pista sin letra y la guarda para siempre en tu teléfono para escuchar offline.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAutoLyricsDownload,
                            onCheckedChange = {
                                isAutoLyricsDownload = it
                                LyricsHelper.setAutoLyricsDownload(context, it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Opción 3: Desactivar búsquedas en línea por completo
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Modo Sin Conexión Total",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Desactiva por completo cualquier intento de consulta a internet en la app (carátulas y letras).",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAllSearchDisabled,
                            onCheckedChange = {
                                isAllSearchDisabled = it
                                MusicMetadataSearchService.setOnlineSearchDisabled(context, it)
                            }
                        )
                    }
                }
            }
        }

        // SECCIÓN 1: TEMPORIZADOR DE APAGADO (SLEEP TIMER)
        item {
            Text(
                text = "Temporizador de Apagado (Sleep Timer)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Apagar música automáticamente",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = if (remainingSleepSec != null && remainingSleepSec > 0)
                                        "Tiempo restante: ${SleepTimerManager.formatRemaining()}"
                                    else
                                        "Inactivo (toca para programar)",
                                    fontSize = 12.sp,
                                    color = if (remainingSleepSec != null && remainingSleepSec > 0)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (remainingSleepSec != null && remainingSleepSec > 0) {
                            IconButton(onClick = onCancelSleepTimer) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Cancelar",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onOpenSleepTimerDialog,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (remainingSleepSec != null && remainingSleepSec > 0)
                                "Ajustar o Cancelar Temporizador"
                            else
                                "Programar Temporizador"
                        )
                    }
                }
            }
        }

        // SECCIÓN 2: PANTALLA DE BLOQUEO INMERSIVA
        item {
            Text(
                text = "Pantalla de Bloqueo Inmersiva",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mostrar al bloquear teléfono",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Abre automáticamente la carátula y controles expandidos de alta definición al encender la pantalla.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isLockEnabled,
                            onCheckedChange = onLockEnabledChange
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedButton(
                        onClick = onTestLockScreen,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhoneIphone, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ver Pantalla de Bloqueo Ahora")
                    }
                }
            }
        }

        // SECCIÓN 3: FILTROS DE BIBLIOTECA (ANTI-WHATSAPP Y AUDIOS CORTOS)
        item {
            Text(
                text = "Filtros de Biblioteca",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            val repo = remember { MusicRepository(context) }
            var filterWhatsApp by remember { mutableStateOf(repo.isFilterWhatsAppEnabled()) }
            var minDuration by remember { mutableStateOf(repo.getMinDurationSec()) }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Ocultar WhatsApp y notas de voz
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ocultar audios de WhatsApp",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Excluye automáticamente notas de voz, audios de chat y tonos para no mezclarlos con tus canciones.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = filterWhatsApp,
                            onCheckedChange = {
                                filterWhatsApp = it
                                repo.setFilterWhatsApp(it)
                                onReloadLibrary()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Duración mínima de canciones
                    Column {
                        Text(
                            text = "Duración mínima para incluir",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Ignora archivos de audio cortos (tonos, ringtones, efectos).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Sin filtro", 15 to "> 15s", 30 to "> 30s", 60 to "> 60s").forEach { (sec, label) ->
                                val isSelected = minDuration == sec
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        minDuration = sec
                                        repo.setMinDurationSec(sec)
                                        onReloadLibrary()
                                    },
                                    label = { Text(label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // SECCIÓN 4: AUDIO Y TRANSICIONES (ELIMINACIÓN DE SILENCIOS)
        item {
            Text(
                text = "Audio y Transiciones",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            val playbackPrefs = remember { context.getSharedPreferences("player_playback_prefs", android.content.Context.MODE_PRIVATE) }
            var skipSilence by remember { mutableStateOf(playbackPrefs.getBoolean("skip_silence", false)) }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Eliminar silencios entre canciones",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Salta los segundos de silencio muerto al final y principio de canciones para que la música fluya sin pausas secas.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = {
                                skipSilence = it
                                playbackPrefs.edit().putBoolean("skip_silence", it).apply()
                                MusicPlaybackService.instance?.player?.skipSilenceEnabled = it
                            }
                        )
                    }
                }
            }
        }

        // SECCIÓN 5: BLUETOOTH Y AUDIO COMPARTIDO
        item {
            Text(
                text = "Bluetooth y Dispositivos de Audio",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            val playbackPrefs = remember { context.getSharedPreferences("player_playback_prefs", android.content.Context.MODE_PRIVATE) }
            var bluetoothAutoResume by remember { mutableStateOf(playbackPrefs.getBoolean("bluetooth_auto_resume", false)) }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reanudar al conectar Bluetooth",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Reanuda automáticamente la música cuando tus audífonos o el coche se conectan.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bluetoothAutoResume,
                            onCheckedChange = {
                                bluetoothAutoResume = it
                                playbackPrefs.edit().putBoolean("bluetooth_auto_resume", it).apply()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Compartir Audio (Auracast / Audio Dual)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Conecta múltiples audífonos para escuchar la misma canción. En Android 13+ (Pixel 7) y Samsung, esto se configura directamente en los ajustes de Bluetooth del sistema.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Abrir Ajustes de Bluetooth y Audio Dual")
                    }
                }
            }
        }

        // SECCIÓN 6: ACERCA DE Y ACTUALIZACIONES
        item {
            Text(
                text = "Acerca de la Aplicación y Actualizaciones",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Aura Music Player", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                "Versión $currentVersionName (Compilación $currentVersionCode)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "Reproductor de música de alta fidelidad con AndroidX Media3 (ExoPlayer). Incluye clasificador de Álbumes y Artistas estilo BlackPlayer, selector de vista compacta/nítida, Editor de Metadatos con carátulas HD y Pantalla de Bloqueo inmersiva con fondos dinámicos Palette.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Botón para Comprobar Actualizaciones
                    OutlinedButton(
                        onClick = {
                            if (!isCheckingUpdate) {
                                isCheckingUpdate = true
                                updateCheckStatus = null
                                coroutineScope.launch {
                                    val result = AppUpdateManager.checkUpdate(context, isManualCheck = true)
                                    isCheckingUpdate = false
                                    result.onSuccess { info ->
                                        if (info.hasUpdate) {
                                            updateCheckStatus = "¡Nueva versión v${info.versionName} disponible!"
                                            onTriggerUpdateDialog(info)
                                        } else {
                                            updateCheckStatus = "Tu app está al día (v$currentVersionName)"
                                            Toast.makeText(context, "¡Tienes la última versión!", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure { err ->
                                        val msg = err.localizedMessage ?: "Error de conexión"
                                        updateCheckStatus = "No se pudo verificar: $msg"
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Comprobando actualizaciones...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar Actualizaciones", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    updateCheckStatus?.let { status ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = status,
                            fontSize = 11.sp,
                            color = if (status.startsWith("No")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Fila de Canción con altura fija de 62dp para scroll fluido a 60/120 FPS y botón de edición
 */
@Composable
fun SongRowItem(
    song: Song,
    isSelected: Boolean,
    rankingIndex: Int? = null,
    showRecentlyAddedTag: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onToggleFavorite: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else
            Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Posición de Ranking (#1, #2, #3...) en vista Más Escuchadas
            if (rankingIndex != null) {
                val badgeColor = when (rankingIndex) {
                    1 -> Color(0xFFFFD700) // Oro
                    2 -> Color(0xFFC0C0C0) // Plata
                    3 -> Color(0xFFCD7F32) // Bronce
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                }
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .padding(end = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "#$rankingIndex",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (rankingIndex <= 3) 13.sp else 11.sp,
                        color = badgeColor
                    )
                }
            }

            OptimizedSongThumbnail(
                artUri = song.albumArtUri,
                targetSize = 180,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Sonando",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = song.title,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val subtitleText = when {
                    rankingIndex != null -> "${song.artist} • 🔥 ${song.playCount} ${if (song.playCount == 1) "reproducción" else "reproducciones"}"
                    showRecentlyAddedTag -> "${song.artist} • 🕒 Añadida recientemente"
                    else -> "${song.artist} • ${song.album}"
                }

                Text(
                    text = subtitleText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = song.getFormattedDuration(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 2.dp, end = 2.dp)
            )

            // Botón de favorito (corazón rápido)
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (song.isFavorite) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(17.dp)
                )
            }

            // Botón de opciones / editar información
            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Editar metadata",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * Decodificación de carátulas de alta nitidez sin pixelado:
 * Calidad de color nativa ARGB_8888 + memoria caché + tamaño dinámico + anti-aliasing bicúbico
 */
@Composable
fun OptimizedSongThumbnail(
    artUri: Uri?,
    modifier: Modifier = Modifier,
    targetSize: Int = 200,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    if (artUri == null || artUri == Uri.EMPTY || failedArtworkUris.contains(artUri)) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color(0xFF222224)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(24.dp)
            )
        }
        return
    }

    val context = LocalContext.current
    val imageRequest = remember(artUri, targetSize) {
        ImageRequest.Builder(context)
            .data(artUri)
            .size(targetSize, targetSize)
            .precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .listener(
                onError = { _, _ ->
                    failedArtworkUris.add(artUri)
                }
            )
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = null,
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF222224)),
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Medium
    )
}

/**
 * Mini Reproductor Flotante estilo Apple Music
 */
@Composable
fun FloatingMiniPlayer(
    currentSong: Song?,
    isPlaying: Boolean,
    repeatMode: Int = 2,
    isShuffleEnabled: Boolean = false,
    currentPositionMs: Long = 0L,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onClick: () -> Unit
) {
    val duration = currentSong?.durationMs?.coerceAtLeast(1L) ?: 1L
    val progress = (currentPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de progreso delgada en el borde superior del mini-reproductor
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OptimizedSongThumbnail(
                    artUri = currentSong?.albumArtUri,
                    targetSize = 160,
                    modifier = Modifier.size(44.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSong?.title ?: "",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSong?.artist ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Botón Aleatorio (Shuffle)
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Modo Aleatorio",
                        tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Botón Anterior
                IconButton(onClick = onPrevious, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Anterior",
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Botón Play / Pause
                IconButton(onClick = onPlayPauseToggle, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Botón Siguiente
                IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Siguiente",
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Botón Repetir (Off / All / One)
                IconButton(onClick = onToggleRepeat, modifier = Modifier.size(34.dp)) {
                    val isRepeatActive = repeatMode != 0 // REPEAT_MODE_OFF
                    val repeatIcon = if (repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repetir",
                        tint = if (isRepeatActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SearchBarField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = "Buscar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Borrar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}


@Composable
fun PermissionRequiredCard(
    onRequestPermission: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Acceso a música requerido",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Concede acceso a los archivos de audio para reproducir la música de tu teléfono.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Conceder Permiso")
            }
        }
    }
}
