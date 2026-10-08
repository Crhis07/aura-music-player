package com.musicplayer.ioslockscreen.ui.main

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.musicplayer.ioslockscreen.R
import com.musicplayer.ioslockscreen.data.LyricsHelper
import com.musicplayer.ioslockscreen.data.LyricsResult
import com.musicplayer.ioslockscreen.data.MusicMetadataSearchService
import com.musicplayer.ioslockscreen.model.Song
import com.musicplayer.ioslockscreen.ui.lockscreen.LockScreenActivity
import android.widget.Toast
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedPlayerBottomSheet(
    currentSong: Song?,
    isPlaying: Boolean,
    repeatMode: Int,
    isShuffleEnabled: Boolean,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onDismissRequest: () -> Unit
) {
    if (currentSong == null) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var lyricsResult by remember(currentSong.id) { mutableStateOf<LyricsResult?>(null) }
    var isLyricsExpanded by remember { mutableStateOf(false) }
    var isSearchingLyrics by remember(currentSong.id) { mutableStateOf(false) }
    var searchLyricsError by remember(currentSong.id) { mutableStateOf<String?>(null) }
    var showEditLyricsDialog by remember { mutableStateOf(false) }
    var isCustomSearchingLyrics by remember { mutableStateOf(false) }
    var customSearchLyricsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentSong.id) {
        isSearchingLyrics = false
        searchLyricsError = null
        lyricsResult = LyricsHelper.loadLyricsForSong(context, currentSong)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxHeight(0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Barra superior del reproductor expandido
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Outlined.Close, contentDescription = "Cerrar")
                }

                Text(
                    text = "REPRODUCIENDO AHORA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                IconButton(
                    onClick = {
                        val intent = Intent(context, LockScreenActivity::class.java)
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Pantalla de Bloqueo",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Carátula grande del Álbum
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .shadow(16.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (currentSong.albumArtUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(currentSong.albumArtUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = currentSong.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Título, Artista y Botón de Favorito (❤️)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSong.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSong.artist,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Botón Favorito (❤️) interactivo
                IconButton(
                    onClick = { onToggleFavorite(currentSong) },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (currentSong.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorito",
                        tint = if (currentSong.isFavorite) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Barra de progreso y tiempos
            val duration = currentSong.durationMs.coerceAtLeast(1L)
            var sliderPosition by remember { mutableStateOf<Float?>(null) }
            val currentPos = sliderPosition ?: currentPositionMs.toFloat()

            Slider(
                value = (currentPos / duration.toFloat()).coerceIn(0f, 1f),
                onValueChange = { fraction ->
                    sliderPosition = fraction * duration.toFloat()
                },
                onValueChangeFinished = {
                    sliderPosition?.let { onSeekTo(it.toLong()) }
                    sliderPosition = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = Song.formatMs(currentPos.toLong()),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = Song.formatRemainingMs(currentPos.toLong(), duration),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Controles de Reproducción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Modo Aleatorio (Shuffle)
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Aleatorio",
                        tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Canción Anterior
                IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Anterior",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play / Pause Principal (Botón Grande Circular)
                FilledIconButton(
                    onClick = onPlayPauseToggle,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Canción Siguiente
                IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Siguiente",
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Modo Repetir
                IconButton(onClick = onToggleRepeat, modifier = Modifier.size(48.dp)) {
                    val isRepeatActive = repeatMode != 0
                    val repeatIcon = if (repeatMode == 1) Icons.Default.RepeatOne else Icons.Default.Repeat
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repetir",
                        tint = if (isRepeatActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selector de Salida de Audio / Bluetooth
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bluetooth,
                        contentDescription = "Salida de audio",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Salida de Audio / Auracast", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 🎵 PANEL DE LETRAS ESTILO SPOTIFY
            SpotifyLyricsCard(
                lyricsResult = lyricsResult,
                currentPositionMs = currentPositionMs,
                isExpanded = isLyricsExpanded,
                isSearching = isSearchingLyrics,
                errorMessage = searchLyricsError,
                onToggleExpand = { isLyricsExpanded = !isLyricsExpanded },
                onOpenEditDialog = {
                    customSearchLyricsError = null
                    showEditLyricsDialog = true
                },
                onSearchOnline = {
                    coroutineScope.launch {
                        isSearchingLyrics = true
                        searchLyricsError = null
                        if (MusicMetadataSearchService.isOnlineSearchDisabled(context)) {
                            searchLyricsError = "El 'Modo Sin Conexión Total' está activado en Ajustes."
                            isSearchingLyrics = false
                            return@launch
                        }
                        if (MusicMetadataSearchService.isWifiOnly(context) && MusicMetadataSearchService.isMeteredConnection(context)) {
                            searchLyricsError = "Bloqueado: 'Solo Wi-Fi' está activo y estás conectado a datos móviles."
                            isSearchingLyrics = false
                            return@launch
                        }
                        val result = LyricsHelper.fetchOnlineLyricsManually(context, currentSong)
                        if (result.hasLyrics) {
                            lyricsResult = result
                            searchLyricsError = null
                        } else {
                            searchLyricsError = "No se encontró letra disponible para esta canción en LRCLIB."
                        }
                        isSearchingLyrics = false
                    }
                }
            )

            // Modal para editar, re-buscar o ingresar letra manualmente
            if (showEditLyricsDialog) {
                EditLyricsDialog(
                    song = currentSong,
                    currentLyrics = lyricsResult,
                    isSearching = isCustomSearchingLyrics,
                    searchError = customSearchLyricsError,
                    onDismiss = { showEditLyricsDialog = false },
                    onSearchWithQuery = { customTitle, customArtist ->
                        coroutineScope.launch {
                            isCustomSearchingLyrics = true
                            customSearchLyricsError = null
                            val result = LyricsHelper.searchOnlineLyricsCustom(context, currentSong, customTitle, customArtist)
                            isCustomSearchingLyrics = false
                            if (result.hasLyrics) {
                                lyricsResult = result
                                showEditLyricsDialog = false
                                Toast.makeText(context, "¡Letra encontrada y guardada!", Toast.LENGTH_SHORT).show()
                            } else {
                                customSearchLyricsError = "No se encontró letra para '$customTitle' de '$customArtist' en LRCLIB."
                            }
                        }
                    },
                    onSaveManualLyrics = { text ->
                        val result = LyricsHelper.saveCustomLyrics(context, currentSong, text)
                        lyricsResult = result
                        showEditLyricsDialog = false
                        Toast.makeText(context, "Letra guardada correctamente", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteLyrics = {
                        LyricsHelper.deleteCachedLyrics(context, currentSong)
                        lyricsResult = LyricsResult(isSynced = false, lines = emptyList(), hasLyrics = false)
                        showEditLyricsDialog = false
                        Toast.makeText(context, "Letra eliminada", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

/**
 * Mini-panel y vista de letras estilo Spotify con soporte de sincronización y descarga offline
 */
@Composable
fun SpotifyLyricsCard(
    lyricsResult: LyricsResult?,
    currentPositionMs: Long,
    isExpanded: Boolean,
    isSearching: Boolean,
    errorMessage: String?,
    onToggleExpand: () -> Unit,
    onOpenEditDialog: () -> Unit,
    onSearchOnline: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Cabecera del panel de letras
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onToggleExpand)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lyrics,
                        contentDescription = "Letras",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Letras",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Botón para editar / buscar de nuevo
                    IconButton(
                        onClick = onOpenEditDialog,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Editar o buscar de nuevo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = if (isExpanded) "Reducir" else "Ampliar",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                lyricsResult == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cargando letras...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                lyricsResult.hasLyrics && lyricsResult.isSynced && lyricsResult.lines.isNotEmpty() -> {
                    // Letras sincronizadas en tiempo real con línea activa destacada
                    val activeIndex = remember(currentPositionMs, lyricsResult) {
                        lyricsResult.lines.indexOfLast { it.timeMs <= currentPositionMs }.coerceAtLeast(0)
                    }

                    val linesToShow = if (isExpanded) {
                        lyricsResult.lines
                    } else {
                        // Modo minipanel (3 a 4 líneas centradas en la activa)
                        val start = (activeIndex - 1).coerceAtLeast(0)
                        val end = (activeIndex + 3).coerceAtMost(lyricsResult.lines.size)
                        lyricsResult.lines.subList(start, end)
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleExpand)
                    ) {
                        linesToShow.forEach { line ->
                            val isCurrentLine = activeIndex >= 0 && activeIndex < lyricsResult.lines.size && lyricsResult.lines[activeIndex] == line
                            Text(
                                text = line.text,
                                fontSize = if (isCurrentLine) 16.sp else 14.sp,
                                fontWeight = if (isCurrentLine) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isCurrentLine) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                },
                                lineHeight = 22.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Indicador de procedencia / offline y botón para cambiar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (lyricsResult.source == "cache" || lyricsResult.source == "online") Icons.Default.CloudDone else Icons.Default.Storage,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = when (lyricsResult.source) {
                                    "cache" -> "Guardada offline"
                                    "online" -> "Descargada de LRCLIB"
                                    "demo" -> "Demostración sincronizada"
                                    else -> "Archivo local (.lrc)"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }

                        TextButton(
                            onClick = onOpenEditDialog,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cambiar letra", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                lyricsResult.hasLyrics && !lyricsResult.plainText.isNullOrBlank() -> {
                    // Letra no sincronizada en texto plano
                    Text(
                        text = if (isExpanded) lyricsResult.plainText else lyricsResult.plainText.lines().take(4).joinToString("\n"),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        lineHeight = 22.sp,
                        modifier = Modifier.clickable(onClick = onToggleExpand)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDone,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (lyricsResult.source == "cache" || lyricsResult.source == "online")
                                    "Texto guardado offline"
                                else
                                    "Archivo local (.txt)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }

                        TextButton(
                            onClick = onOpenEditDialog,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cambiar letra", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                else -> {
                    // Sin letra sincronizada: estado elegante con opción de búsqueda manual en línea
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sin letra sincronizada",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Esta pista no incluye archivo .lrc en tu teléfono. Puedes buscarla en línea en LRCLIB con 1 toque y quedará guardada permanentemente para reproducirla sin internet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 16.sp
                        )

                        if (errorMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = errorMessage,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        if (isSearching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Buscando letra en LRCLIB...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onSearchOnline,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Buscar en Línea",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                OutlinedButton(
                                    onClick = onOpenEditDialog,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Personalizar",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diálogo modal para buscar letras con términos personalizados, escribir/pegar texto o eliminarlas
 */
@Composable
fun EditLyricsDialog(
    song: Song,
    currentLyrics: LyricsResult?,
    isSearching: Boolean,
    searchError: String?,
    onDismiss: () -> Unit,
    onSearchWithQuery: (title: String, artist: String) -> Unit,
    onSaveManualLyrics: (text: String) -> Unit,
    onDeleteLyrics: () -> Unit
) {
    var searchTitle by remember(song.id) {
        mutableStateOf(
            song.title.replace(Regex("""\.(mp3|flac|wav|m4a|aac|ogg|wma)$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\(.*?\)|\{.*?\}|\[.*?\]"""), "").trim()
        )
    }
    var searchArtist by remember(song.id) {
        mutableStateOf(
            if (song.artist.contains("Desconocido", true) || song.artist.contains("Unknown", true)) "" else song.artist.trim()
        )
    }
    var manualText by remember(song.id, currentLyrics) {
        mutableStateOf(
            if (currentLyrics?.isSynced == true && currentLyrics.lines.isNotEmpty()) {
                currentLyrics.lines.joinToString("\n") { it.text }
            } else {
                currentLyrics?.plainText ?: ""
            }
        )
    }
    var selectedTab by remember { mutableStateOf(0) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Letras de Canción",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cerrar", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Selector de modo: Buscar en LRCLIB vs Manual
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = 0 }
                    ) {
                        Text(
                            text = "Buscar en LRCLIB",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = 1 }
                    ) {
                        Text(
                            text = "Escribir / Pegar",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    Text(
                        text = "Edita el título o artista para corregir la búsqueda en línea:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchTitle,
                        onValueChange = { searchTitle = it },
                        label = { Text("Título de la canción") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchArtist,
                        onValueChange = { searchArtist = it },
                        label = { Text("Nombre del artista") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (searchError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = searchError,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { onSearchWithQuery(searchTitle, searchArtist) },
                        enabled = !isSearching && searchTitle.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscando en LRCLIB...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Buscar y Guardar Letra", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text(
                        text = "Pega o edita el texto de la letra. Si incluye formato [.lrc], se sincronizará automáticamente:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        placeholder = { Text("Escribe o pega aquí la letra...") },
                        minLines = 4,
                        maxLines = 7,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { onSaveManualLyrics(manualText) },
                        enabled = manualText.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Guardar Letra", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (currentLyrics?.hasLyrics == true) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onDeleteLyrics,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Eliminar Letra Guardada", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
