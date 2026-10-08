package com.musicplayer.ioslockscreen.ui.lockscreen

import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.musicplayer.ioslockscreen.R
import com.musicplayer.ioslockscreen.model.Song
import com.musicplayer.ioslockscreen.ui.components.DynamicGradientBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun IOSLockScreenView(
    currentSong: Song?,
    isPlaying: Boolean,
    repeatMode: Int = Player.REPEAT_MODE_ALL,
    isShuffleEnabled: Boolean = false,
    currentPositionMs: Long,
    albumArtBitmap: Bitmap?,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleRepeat: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDismissToUnlock: () -> Unit
) {
    val context = LocalContext.current
    val is24Hour = remember { android.text.format.DateFormat.is24HourFormat(context) }

    // Fecha y hora en vivo adaptadas al formato del teléfono del usuario
    val currentTimeStr = remember { mutableStateOf("") }
    val currentDateStr = remember { mutableStateOf("") }
    var isCoverExpanded by remember { mutableStateOf(true) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(feedbackMessage) {
        if (feedbackMessage != null) {
            delay(1600)
            feedbackMessage = null
        }
    }

    fun refreshDateTime() {
        val now = Date()
        val timePattern = if (is24Hour) "HH:mm" else "h:mm"
        val timeFormatter = SimpleDateFormat(timePattern, Locale.getDefault())
        currentTimeStr.value = timeFormatter.format(now)

        val datePattern = "EEEE, d 'de' MMMM"
        val dateFormatter = SimpleDateFormat(datePattern, Locale.getDefault())
        val formattedDate = dateFormatter.format(now).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        currentDateStr.value = formattedDate
    }

    LaunchedEffect(Unit) {
        refreshDateTime()
        while (true) {
            kotlinx.coroutines.delay(1000)
            refreshDateTime()
        }
    }

    val totalDuration = (currentSong?.durationMs ?: 1L).coerceAtLeast(1L)
    val offsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    DynamicGradientBackground(bitmap = albumArtBitmap) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer {
                    // Desvanecer sutilmente mientras se desliza hacia arriba
                    alpha = (1f - (-offsetY.value / 1200f)).coerceIn(0.15f, 1f)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                // Si se deslizó hacia arriba más de 200 píxeles, completar salida fluida
                                if (offsetY.value < -220f) {
                                    offsetY.animateTo(
                                        targetValue = -2500f,
                                        animationSpec = tween(durationMillis = 220)
                                    )
                                    onDismissToUnlock()
                                    offsetY.snapTo(0f)
                                } else {
                                    // Si el gesto fue incompleto, rebote elástico hacia su posición original
                                    offsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onVerticalDrag = { change, dragAmount ->
                            // Acompañar el dedo en tiempo real 1:1
                            if (dragAmount < 0 || offsetY.value < 0) {
                                change.consume()
                                coroutineScope.launch {
                                    val newOffset = (offsetY.value + dragAmount).coerceAtMost(0f)
                                    offsetY.snapTo(newOffset)
                                }
                            }
                        }
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {

                // 1. Cabecera iOS 16: Fecha y Reloj de alta fidelidad
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(
                        text = currentDateStr.value,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        letterSpacing = 0.3.sp,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.35f),
                                offset = Offset(0f, 2f),
                                blurRadius = 8f
                            )
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentTimeStr.value,
                        fontSize = 76.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = (-1.5).sp,
                        lineHeight = 76.sp,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.35f),
                                offset = Offset(0f, 4f),
                                blurRadius = 16f
                            )
                        )
                    )
                }

                // 2. Carátula Central (Diseño optimizado, enmarcado con cristal y filtro de alta nitidez)
                AnimatedVisibility(
                    visible = isCoverExpanded,
                    enter = fadeIn(tween(250)) + androidx.compose.animation.scaleIn(initialScale = 0.88f),
                    exit = fadeOut(tween(200)) + androidx.compose.animation.scaleOut(targetScale = 0.88f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .widthIn(max = 355.dp)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        // Halo de profundidad ambiental estilo iOS 16
                        Box(
                            modifier = Modifier
                                .fillMaxSize(0.96f)
                                .shadow(
                                    elevation = 32.dp,
                                    shape = RoundedCornerShape(28.dp),
                                    ambientColor = Color.Black.copy(alpha = 0.65f),
                                    spotColor = Color.Black.copy(alpha = 0.85f)
                                )
                        )

                        // Tarjeta de la carátula con bisel y anti-aliasing de máxima nitidez
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = Color.Black.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { isCoverExpanded = false }
                        ) {
                            if (albumArtBitmap != null) {
                                Image(
                                    bitmap = albumArtBitmap.asImageBitmap(),
                                    contentDescription = "Carátula del Álbum (Toca para vista compacta)",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    filterQuality = FilterQuality.High
                                )
                            } else if (currentSong?.albumArtUri != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentSong.albumArtUri)
                                        .crossfade(true)
                                        .precision(Precision.EXACT)
                                        .build(),
                                    contentDescription = "Carátula (Toca para vista compacta)",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    filterQuality = FilterQuality.High
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(54.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Aura Music",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Tarjeta Glassmorphic Inferior de Controles Multimedia
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.22f)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Fila de Título y Artista (con miniatura ultra nítida cuando la carátula principal está colapsada)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isCoverExpanded = !isCoverExpanded }
                        ) {
                            if (!isCoverExpanded) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .shadow(8.dp, RoundedCornerShape(12.dp))
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (albumArtBitmap != null) {
                                        Image(
                                            bitmap = albumArtBitmap.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            filterQuality = FilterQuality.High
                                        )
                                    } else if (currentSong?.albumArtUri != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(currentSong.albumArtUri)
                                                .crossfade(true)
                                                .precision(Precision.EXACT)
                                                .build(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            filterQuality = FilterQuality.High
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                            }

                            Column(
                                horizontalAlignment = if (isCoverExpanded) Alignment.CenterHorizontally else Alignment.Start,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = currentSong?.title ?: "Sin reproducir",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = if (isCoverExpanded) TextAlign.Center else TextAlign.Start,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            repeatDelayMillis = 1200,
                                            initialDelayMillis = 2000,
                                            velocity = 32.dp
                                        )
                                )
                                Text(
                                    text = "${currentSong?.artist ?: "Selecciona una pista"}${if (!currentSong?.album.isNullOrBlank()) " — " + currentSong?.album else ""}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = if (isCoverExpanded) TextAlign.Center else TextAlign.Start,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp)
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            repeatDelayMillis = 1200,
                                            initialDelayMillis = 2500,
                                            velocity = 28.dp
                                        )
                                )
                            }

                            if (currentSong != null) {
                                IconButton(
                                    onClick = {
                                        val willBeFav = !currentSong.isFavorite
                                        onToggleFavorite()
                                        feedbackMessage = if (willBeFav) "Añadido a Favoritos ❤️" else "Eliminado de Favoritos"
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (currentSong.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Favorito",
                                        tint = if (currentSong.isFavorite) Color(0xFFFF2D55) else Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Barra de Progreso Continua Scrubber estilo iOS
                        IOSScrubber(
                            positionMs = currentPositionMs,
                            totalDurationMs = totalDuration,
                            onSeek = onSeek
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Indicador flotante sutil de estado (ej: "Repetir todo", "Modo aleatorio")
                        AnimatedVisibility(
                            visible = feedbackMessage != null,
                            enter = fadeIn(tween(150)) + expandVertically(),
                            exit = fadeOut(tween(200)) + shrinkVertically()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.22f),
                                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.35f)),
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                Text(
                                    text = feedbackMessage ?: "",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // Botones de reproducción iOS completos: Shuffle, Anterior, Play/Pause, Siguiente, Repetir
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Aleatorio (Shuffle)
                            IconButton(
                                onClick = {
                                    onToggleShuffle()
                                    feedbackMessage = if (!isShuffleEnabled) "Aleatorio activado" else "Aleatorio desactivado"
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = "Modo Aleatorio",
                                        tint = if (isShuffleEnabled) Color(0xFF38EF7D) else Color.White.copy(alpha = 0.45f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    if (isShuffleEnabled) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(top = 22.dp)
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF38EF7D))
                                        )
                                    }
                                }
                            }

                            // 2. Anterior
                            IconButton(
                                onClick = onPrevious,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Anterior",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // 3. Play / Pause
                            IconButton(
                                onClick = onPlayPauseToggle,
                                modifier = Modifier.size(52.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                    tint = Color.White,
                                    modifier = Modifier.size(44.dp)
                                )
                            }

                            // 4. Siguiente
                            IconButton(
                                onClick = onNext,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Siguiente",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            // 5. Repetición (Off, All, One)
                            IconButton(
                                onClick = {
                                    onToggleRepeat()
                                    feedbackMessage = when (repeatMode) {
                                        Player.REPEAT_MODE_OFF -> "Repetir lista completa"
                                        Player.REPEAT_MODE_ALL -> "Repetir canción actual"
                                        Player.REPEAT_MODE_ONE -> "Repetición desactivada"
                                        else -> "Repetir lista"
                                    }
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF
                                    val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                                    val repeatColor = if (isRepeatActive) Color(0xFF38EF7D) else Color.White.copy(alpha = 0.45f)

                                    Icon(
                                        imageVector = repeatIcon,
                                        contentDescription = "Modo de Repetición",
                                        tint = repeatColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    if (isRepeatActive) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .padding(top = 22.dp)
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF38EF7D))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Accesos directos inferiores de Linterna y Cámara + Barra de desbloqueo iOS
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botón Linterna Linterna estilo iOS
                        var isFlashOn by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isFlashOn) Color.White else Color.White.copy(alpha = 0.18f)
                                )
                                .clickable {
                                    isFlashOn = !isFlashOn
                                    toggleFlashlight(context, isFlashOn)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlashlightOn,
                                contentDescription = "Linterna",
                                tint = if (isFlashOn) Color.Black else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Indicador de Desbloqueo (Texto sutil)
                        Text(
                            text = "Desliza para abrir",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.3.sp
                        )

                        // Botón Cámara estilo iOS
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f))
                                .clickable {
                                    try {
                                        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                                        context.startActivity(cameraIntent)
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Cámara",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Home indicator elegante de iOS (Línea blanca redondeada)
                    Box(
                        modifier = Modifier
                            .width(134.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.65f))
                            .clickable {
                                coroutineScope.launch {
                                    offsetY.animateTo(-2500f, tween(240))
                                    onDismissToUnlock()
                                    offsetY.snapTo(0f)
                                }
                            }
                    )
                }
            }
        }
    }
}

/**
 * Scrubber continuo estilo iOS con respuesta al toque y arrastre sin cortes ni separaciones
 */
@Composable
fun IOSScrubber(
    positionMs: Long,
    totalDurationMs: Long,
    onSeek: (Long) -> Unit
) {
    val total = totalDurationMs.coerceAtLeast(1L)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val currentFraction = (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val displayFraction = if (isDragging) dragFraction else currentFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(totalDurationMs) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((fraction * total).toLong())
                    }
                }
                .pointerInput(totalDurationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            onSeek((dragFraction * total).toLong())
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val trackHeight = if (isDragging) 6.dp else 4.dp
            val thumbRadius = if (isDragging) 7.dp else 5.dp

            // Pista de fondo (inactiva)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
            )

            // Pista de progreso (activa)
            Box(
                modifier = Modifier
                    .fillMaxWidth(displayFraction)
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(Color.White)
            )

            // Perilla / Thumb minimalista
            val offsetX = (maxWidth * displayFraction) - thumbRadius
            Box(
                modifier = Modifier
                    .offset(x = offsetX.coerceAtLeast(0.dp))
                    .size(thumbRadius * 2)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }

        // Tiempos formateados
        val displayMs = if (isDragging) (dragFraction * total).toLong() else positionMs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = Song.formatMs(displayMs),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.65f)
            )
            Text(
                text = Song.formatRemainingMs(displayMs, total),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.65f)
            )
        }
    }
}

private fun toggleFlashlight(context: android.content.Context, enable: Boolean) {
    try {
        val cameraManager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as? CameraManager
        val cameraId = cameraManager?.cameraIdList?.firstOrNull()
        if (cameraId != null) {
            cameraManager.setTorchMode(cameraId, enable)
        }
    } catch (e: Exception) {
        // En emuladores o dispositivos sin flash
    }
}
