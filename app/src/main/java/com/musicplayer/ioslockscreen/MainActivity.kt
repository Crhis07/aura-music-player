package com.musicplayer.ioslockscreen

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.musicplayer.ioslockscreen.data.MusicRepository
import com.musicplayer.ioslockscreen.model.Album
import com.musicplayer.ioslockscreen.model.Artist
import com.musicplayer.ioslockscreen.model.MusicFolder
import com.musicplayer.ioslockscreen.model.Song
import com.musicplayer.ioslockscreen.model.toMediaItem
import com.musicplayer.ioslockscreen.service.MusicPlaybackService
import com.musicplayer.ioslockscreen.ui.main.MainPlayerScreen
import com.musicplayer.ioslockscreen.ui.theme.IOSMusicPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: MusicRepository
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    private val _folders = MutableStateFlow<List<MusicFolder>>(emptyList())
    private val _currentSong = MutableStateFlow<Song?>(null)
    private val _isPlaying = MutableStateFlow(false)
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_ALL)
    private val _isShuffleEnabled = MutableStateFlow(false)
    private val _currentPosition = MutableStateFlow(0L)
    private val _hasPermission = MutableStateFlow(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        _hasPermission.value = audioGranted
        loadSongs()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MusicRepository(this)

        checkAndRequestPermissions()
        startPlaybackService()
        initMediaController()
        loadSongs()

        // Observador de progreso continuo para alimentar la barra de progreso
        lifecycleScope.launch {
            while (isActive) {
                controller?.let { player ->
                    if (player.isPlaying) {
                        _currentPosition.value = player.currentPosition
                    }
                }
                delay(500)
            }
        }

        setContent {
            IOSMusicPlayerTheme {
                val songs = _songs.asStateFlow().collectAsState().value
                val albums = _albums.asStateFlow().collectAsState().value
                val artists = _artists.asStateFlow().collectAsState().value
                val folders = _folders.asStateFlow().collectAsState().value
                val currentSong = _currentSong.asStateFlow().collectAsState().value
                val isPlaying = _isPlaying.asStateFlow().collectAsState().value
                val repeatMode = _repeatMode.asStateFlow().collectAsState().value
                val isShuffle = _isShuffleEnabled.asStateFlow().collectAsState().value
                val currentPos = _currentPosition.asStateFlow().collectAsState().value
                val hasPerm = _hasPermission.asStateFlow().collectAsState().value

                MainPlayerScreen(
                    songs = songs,
                    albums = albums,
                    artists = artists,
                    folders = folders,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    repeatMode = repeatMode,
                    isShuffleEnabled = isShuffle,
                    currentPositionMs = currentPos,
                    hasPermission = hasPerm,
                    onRequestPermission = { checkAndRequestPermissions() },
                    onSongSelected = { song -> playSong(song) },
                    onToggleFavorite = { song -> toggleSongFavorite(song) },
                    onUpdateSongMetadata = { updatedSong -> updateSongMetadata(updatedSong) },
                    onSeekTo = { posMs -> controller?.seekTo(posMs) },
                    onReloadLibrary = { loadSongs() },
                    onPlayPauseToggle = {
                        controller?.let { player ->
                            if (player.isPlaying) player.pause() else player.play()
                        }
                    },
                    onNext = {
                        controller?.let { player ->
                            if (player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                            } else {
                                player.seekTo(0, 0L)
                            }
                        }
                    },
                    onPrevious = {
                        controller?.let { player ->
                            if (player.currentPosition > 3000L) {
                                player.seekTo(0L)
                            } else if (player.hasPreviousMediaItem()) {
                                player.seekToPreviousMediaItem()
                            } else {
                                player.seekTo(player.mediaItemCount - 1, 0L)
                            }
                        }
                    },
                    onToggleRepeat = { toggleRepeatMode() },
                    onToggleShuffle = { toggleShuffleMode() },
                    onPlayPlaylist = { playlist, shuffle -> playPlaylist(playlist, shuffle) }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (Google Pixel 7)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isEmpty()) {
            _hasPermission.value = true
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun loadSongs() {
        lifecycleScope.launch {
            // Carga y agrupamiento en segundo plano en Dispatchers.IO para arranque instantáneo sin lag
            val library = repository.loadFullLibrary()
            _songs.value = library.songs
            _albums.value = library.albums
            _artists.value = library.artists
            _folders.value = library.folders
            if (_currentSong.value == null && library.songs.isNotEmpty()) {
                _currentSong.value = library.songs.first()
            }
        }
    }

    private fun updateSongMetadata(updatedSong: Song) {
        repository.saveSongOverride(updatedSong)

        // Actualizar canción en la lista en memoria inmediatamente
        val currentList = _songs.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedSong.id }
        if (index >= 0) {
            currentList[index] = updatedSong
            _songs.value = currentList
        }
        if (_currentSong.value?.id == updatedSong.id) {
            _currentSong.value = updatedSong
        }

        // Si la cola del reproductor está activa, actualizar el MediaItem actual
        controller?.let { player ->
            if (player.mediaItemCount == currentList.size && index >= 0) {
                val currentPos = player.currentPosition
                val isPlaying = player.isPlaying
                val mediaItems = currentList.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, player.currentMediaItemIndex, currentPos)
                if (isPlaying) player.play()
            }
        }

        // Recalcular agrupaciones en segundo plano
        lifecycleScope.launch {
            val library = repository.loadFullLibrary()
            _songs.value = library.songs
            _albums.value = library.albums
            _artists.value = library.artists
        }
    }

    private fun startPlaybackService() {
        val serviceIntent = Intent(this, MusicPlaybackService::class.java)
        startService(serviceIntent)
    }

    private fun initMediaController() {
        val sessionToken = SessionToken(this, ComponentName(this, MusicPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _repeatMode.value = repeatMode
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _isShuffleEnabled.value = shuffleModeEnabled
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val currentUri = mediaItem?.requestMetadata?.mediaUri ?: mediaItem?.localConfiguration?.uri
                    val currentMediaId = mediaItem?.mediaId
                    val matchingSong = _songs.value.find {
                        (currentMediaId != null && it.id.toString() == currentMediaId) ||
                        (currentUri != null && it.mediaUri == currentUri)
                    }
                    if (matchingSong != null) {
                        _currentSong.value = matchingSong
                    }
                }
            })
            _isPlaying.value = controller?.isPlaying ?: false
            _repeatMode.value = controller?.repeatMode ?: Player.REPEAT_MODE_ALL
            _isShuffleEnabled.value = controller?.shuffleModeEnabled ?: false
        }, MoreExecutors.directExecutor())
    }

    private fun toggleRepeatMode() {
        val player = controller ?: MusicPlaybackService.instance?.player ?: return
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        player.repeatMode = nextMode
        _repeatMode.value = nextMode
    }

    private fun toggleShuffleMode() {
        val player = controller ?: MusicPlaybackService.instance?.player ?: return
        val nextShuffle = !player.shuffleModeEnabled
        player.shuffleModeEnabled = nextShuffle
        _isShuffleEnabled.value = nextShuffle
    }

    private fun playPlaylist(playlist: List<Song>, shuffle: Boolean = false) {
        if (playlist.isEmpty()) return
        val player = controller ?: MusicPlaybackService.instance?.player ?: return

        val actualList = if (shuffle) playlist.shuffled() else playlist
        val startSong = actualList.first()

        repository.incrementPlayCount(startSong.id)
        _currentSong.value = startSong

        val mediaItems = actualList.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, 0, 0L)
        if (shuffle) {
            player.shuffleModeEnabled = true
            _isShuffleEnabled.value = true
        }
        player.prepare()
        player.play()
    }

    private fun toggleSongFavorite(song: Song) {
        val newFav = repository.toggleFavorite(song.id)
        val list = _songs.value.toMutableList()
        val idx = list.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(isFavorite = newFav)
            _songs.value = list
        }
        if (_currentSong.value?.id == song.id) {
            _currentSong.value = _currentSong.value?.copy(isFavorite = newFav)
        }
    }

    private fun playSong(song: Song) {
        val newCount = repository.incrementPlayCount(song.id)
        val updatedSong = song.copy(playCount = newCount)
        _currentSong.value = updatedSong

        val list = _songs.value.toMutableList()
        val targetIndex = list.indexOfFirst { it.id == song.id }
        if (targetIndex >= 0) {
            list[targetIndex] = updatedSong
            _songs.value = list
        }

        val player = controller ?: MusicPlaybackService.instance?.player ?: return
        val songsList = _songs.value
        val playIndex = if (targetIndex >= 0) targetIndex else 0

        if (player.mediaItemCount == songsList.size && player.mediaItemCount > 0) {
            // Si la cola ya está cargada, saltar instantáneamente sin costo de serialización Binder
            player.seekTo(playIndex, 0L)
            player.play()
        } else {
            val mediaItems = songsList.map { it.toMediaItem() }
            player.setMediaItems(mediaItems, playIndex, 0L)
            player.prepare()
            player.play()
        }
    }

    override fun onDestroy() {
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        super.onDestroy()
    }
}
