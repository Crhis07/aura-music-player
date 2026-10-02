package com.musicplayer.ioslockscreen.ui.lockscreen

import android.app.KeyguardManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musicplayer.ioslockscreen.data.MusicRepository
import com.musicplayer.ioslockscreen.model.Song
import com.musicplayer.ioslockscreen.model.toMediaItem
import com.musicplayer.ioslockscreen.service.MusicPlaybackService
import kotlinx.coroutines.*

class LockScreenActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private var currentSongState = kotlinx.coroutines.flow.MutableStateFlow<Song?>(null)
    private var isPlayingState = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var currentPositionState = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var albumArtBitmapState = kotlinx.coroutines.flow.MutableStateFlow<Bitmap?>(null)
    private var playerState = kotlinx.coroutines.flow.MutableStateFlow<Player?>(null)
    private var repeatModeState = kotlinx.coroutines.flow.MutableStateFlow(Player.REPEAT_MODE_ALL)
    private var isShuffleEnabledState = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    private var playerListener: Player.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-cargar instancia del reproductor local si ya está activo
        val localPlayer = MusicPlaybackService.instance?.player
        playerState.value = localPlayer
        if (localPlayer != null) {
            setupPlayerListener()
            updateCurrentSongFromPlayer()
        }

        // Eliminar animaciones de ventana de la Activity para que no parezca una app superpuesta
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        // Mostrar sobre la pantalla de bloqueo (sin forzar el encendido ante notificaciones)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        // Diseño inmersivo total Edge-to-Edge extendido hasta el corte de la cámara (Display Cutout)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // Ocultar barra de navegación del sistema para evitar barras negras y duplicación de barras
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())

        initMediaController()

        setContent {
            val currentSong = currentSongState.collectAsState().value
            val isPlaying = isPlayingState.collectAsState().value
            val repeatMode = repeatModeState.collectAsState().value
            val isShuffle = isShuffleEnabledState.collectAsState().value
            val positionMs = currentPositionState.collectAsState().value
            val bitmap = albumArtBitmapState.collectAsState().value
            val activePlayer = playerState.collectAsState().value ?: MusicPlaybackService.instance?.player ?: controller

            IOSLockScreenView(
                currentSong = currentSong,
                isPlaying = isPlaying,
                repeatMode = repeatMode,
                isShuffleEnabled = isShuffle,
                currentPositionMs = positionMs,
                albumArtBitmap = bitmap,
                onPlayPauseToggle = {
                    activePlayer?.let { player ->
                        if (player.isPlaying) player.pause() else player.play()
                    }
                },
                onNext = {
                    activePlayer?.let { player ->
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                        } else if (player.mediaItemCount > 0) {
                            player.seekTo(0, 0L)
                        }
                    }
                },
                onPrevious = {
                    activePlayer?.let { player ->
                        if (player.currentPosition > 3000L) {
                            player.seekTo(0L)
                        } else if (player.hasPreviousMediaItem()) {
                            player.seekToPreviousMediaItem()
                        } else if (player.mediaItemCount > 0) {
                            player.seekTo(player.mediaItemCount - 1, 0L)
                        }
                    }
                },
                onSeek = { targetMs ->
                    activePlayer?.seekTo(targetMs)
                    currentPositionState.value = targetMs
                },
                onToggleRepeat = {
                    activePlayer?.let { player ->
                        val next = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                            else -> Player.REPEAT_MODE_ALL
                        }
                        player.repeatMode = next
                        repeatModeState.value = next
                    }
                },
                onToggleShuffle = {
                    activePlayer?.let { player ->
                        val next = !player.shuffleModeEnabled
                        player.shuffleModeEnabled = next
                        isShuffleEnabledState.value = next
                    }
                },
                onToggleFavorite = {
                    currentSong?.let { song ->
                        val repo = MusicRepository(this@LockScreenActivity)
                        val newFav = repo.toggleFavorite(song)
                        currentSongState.value = song.copy(isFavorite = newFav)
                    }
                },
                onDismissToUnlock = {
                    unlockDevice()
                }
            )
        }

        startPositionUpdater()
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            playerState.value = MusicPlaybackService.instance?.player ?: controller
            setupPlayerListener()
            updateCurrentSongFromPlayer()
            ensurePlaylistLoaded()
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        if (playerListener != null) return
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayingState.value = isPlaying
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                repeatModeState.value = repeatMode
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                isShuffleEnabledState.value = shuffleModeEnabled
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentSongFromPlayer()
            }
        }
        playerListener = listener
        val active = MusicPlaybackService.instance?.player ?: controller
        active?.addListener(listener)
        isPlayingState.value = active?.isPlaying ?: false
        repeatModeState.value = active?.repeatMode ?: Player.REPEAT_MODE_ALL
        isShuffleEnabledState.value = active?.shuffleModeEnabled ?: false
    }

    private fun updateCurrentSongFromPlayer() {
        val player = MusicPlaybackService.instance?.player ?: controller ?: return
        val item = player.currentMediaItem ?: return

        val metadata = item.mediaMetadata
        val title = metadata.title?.toString() ?: "Canción"
        val artist = metadata.artist?.toString() ?: "Artista"
        val album = metadata.albumTitle?.toString() ?: "Álbum"
        val duration = player.duration.coerceAtLeast(0L)
        val mediaUri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri ?: Uri.EMPTY
        val songId = item.mediaId.toLongOrNull() ?: 0L

        val repository = MusicRepository(this@LockScreenActivity)
        var isFav = if (songId > 0L) repository.isFavorite(songId) else false
        if (!isFav) {
            isFav = repository.isFavoriteByMetadata(title, artist)
        }

        val song = Song(
            id = songId,
            title = title,
            artist = artist,
            album = album,
            durationMs = if (duration > 0) duration else 180000L,
            mediaUri = mediaUri,
            albumArtUri = metadata.artworkUri,
            isFavorite = isFav
        )
        currentSongState.value = song

        // Extraer imagen bitmap para Palette usando loadThumbnail
        loadBitmapForSong(song, metadata.artworkUri)
    }

    private fun ensurePlaylistLoaded() {
        // No forzamos la sobreescritura de la lista con el índice 0 para no alterar la selección del usuario
    }

    private fun loadBitmapForSong(song: Song?, artUri: Uri?) {
        activityScope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null

            // 1. Si hay artUri (URL remota, archivo local o content URI de alta resolución)
            if (artUri != null && artUri != Uri.EMPTY) {
                try {
                    val loader = coil.ImageLoader(this@LockScreenActivity)
                    val request = coil.request.ImageRequest.Builder(this@LockScreenActivity)
                        .data(artUri)
                        .size(coil.size.Size.ORIGINAL)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(request)
                    if (result is coil.request.SuccessResult) {
                        bmp = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    }
                } catch (e: Exception) {
                    // Ignorar fallback
                }
            }

            // 2. Extraer carátula ID3 embebida de alta calidad directamente del archivo de audio
            if (bmp == null && song != null && song.mediaUri != Uri.EMPTY) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    if (song.mediaUri.scheme == "http" || song.mediaUri.scheme == "https") {
                        retriever.setDataSource(song.mediaUri.toString(), HashMap())
                    } else {
                        retriever.setDataSource(this@LockScreenActivity, song.mediaUri)
                    }
                    val rawPicture = retriever.embeddedPicture
                    if (rawPicture != null) {
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inDither = true
                            inScaled = false
                        }
                        bmp = BitmapFactory.decodeByteArray(rawPicture, 0, rawPicture.size, options)
                    }
                } catch (e: Exception) {
                    // Fallback
                } finally {
                    try { retriever.release() } catch (e: Exception) {}
                }
            }

            // 3. Intentar abrir inputStream directo del artUri de MediaStore
            if (bmp == null && artUri != null && artUri != Uri.EMPTY) {
                try {
                    contentResolver.openInputStream(artUri)?.use { input ->
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        bmp = BitmapFactory.decodeStream(input, null, options)
                    }
                } catch (e: Exception) {
                    // Ignorar fallback
                }
            }

            // 4. Último recurso: Miniatura del sistema Android
            if (bmp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && song != null && song.mediaUri != Uri.EMPTY) {
                try {
                    bmp = contentResolver.loadThumbnail(song.mediaUri, Size(800, 800), null)
                } catch (e: Exception) {
                    // Ignorar fallback
                }
            }

            withContext(Dispatchers.Main) {
                albumArtBitmapState.value = bmp
            }
        }
    }

    private fun startPositionUpdater() {
        activityScope.launch {
            while (isActive) {
                controller?.let { player ->
                    if (player.isPlaying) {
                        currentPositionState.value = player.currentPosition
                    }
                }
                delay(500)
            }
        }
    }

    private fun unlockDevice() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val dismissAction = {
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }

        // Si el teléfono no está realmente bloqueado (p. ej. en pruebas), cerrar directamente
        if (!keyguardManager.isKeyguardLocked) {
            dismissAction()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    dismissAction()
                }
                override fun onDismissCancelled() {
                    // Usuario canceló el desbloqueo
                }
                override fun onDismissError() {
                    dismissAction()
                }
            })
        } else {
            dismissAction()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCurrentSongFromPlayer()
        val active = MusicPlaybackService.instance?.player ?: controller
        isPlayingState.value = active?.isPlaying ?: false
        repeatModeState.value = active?.repeatMode ?: Player.REPEAT_MODE_ALL
        isShuffleEnabledState.value = active?.shuffleModeEnabled ?: false
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateCurrentSongFromPlayer()
        val active = MusicPlaybackService.instance?.player ?: controller
        isPlayingState.value = active?.isPlaying ?: false
        repeatModeState.value = active?.repeatMode ?: Player.REPEAT_MODE_ALL
        isShuffleEnabledState.value = active?.shuffleModeEnabled ?: false
    }

    override fun onDestroy() {
        playerListener?.let { l ->
            MusicPlaybackService.instance?.player?.removeListener(l)
            controller?.removeListener(l)
        }
        activityScope.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
}
