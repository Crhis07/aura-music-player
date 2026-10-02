package com.musicplayer.ioslockscreen.service

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicplayer.ioslockscreen.MainActivity
import com.musicplayer.ioslockscreen.R
import com.musicplayer.ioslockscreen.ui.lockscreen.LockScreenActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class MusicPlaybackService : MediaSessionService() {

    var player: ExoPlayer? = null
        private set
    private var mediaSession: MediaSession? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null

    companion object {
        const val CHANNEL_ID = "playback_channel_id"
        const val NOTIFICATION_ID = 101
        var isLockScreenEnabled: Boolean = true
        var instance: MusicPlaybackService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val prefs = getSharedPreferences("player_playback_prefs", Context.MODE_PRIVATE)
        val savedRepeat = prefs.getInt("repeat_mode", Player.REPEAT_MODE_ALL)
        val savedShuffle = prefs.getBoolean("shuffle_mode", false)
        val skipSilence = prefs.getBoolean("skip_silence", false)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = savedRepeat
                shuffleModeEnabled = savedShuffle
                skipSilenceEnabled = skipSilence
                addListener(object : Player.Listener {
                    override fun onRepeatModeChanged(newRepeatMode: Int) {
                        prefs.edit().putInt("repeat_mode", newRepeatMode).apply()
                    }

                    override fun onShuffleModeEnabledChanged(newShuffleModeEnabled: Boolean) {
                        prefs.edit().putBoolean("shuffle_mode", newShuffleModeEnabled).apply()
                    }
                })
            }

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionCallback = object : MediaSession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                val updatedMediaItems = mediaItems.map { item ->
                    val resolvedUri = item.requestMetadata.mediaUri
                        ?: item.localConfiguration?.uri
                        ?: if (item.mediaId.isNotBlank()) Uri.parse(item.mediaId) else Uri.EMPTY

                    item.buildUpon()
                        .setUri(resolvedUri)
                        .setMediaId(item.mediaId)
                        .setMediaMetadata(item.mediaMetadata)
                        .setRequestMetadata(
                            item.requestMetadata.buildUpon()
                                .setMediaUri(resolvedUri)
                                .build()
                        )
                        .build()
                }
                return Futures.immediateFuture(updatedMediaItems)
            }
        }

        mediaSession = player?.let {
            MediaSession.Builder(this, it)
                .setCallback(sessionCallback)
                .setSessionActivity(sessionActivityPendingIntent)
                .build()
        }

        setupScreenListener()
        setupBluetoothListener()
    }

    private fun setupBluetoothListener() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                val isPluggedIn = action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                        (action == Intent.ACTION_HEADSET_PLUG && intent.getIntExtra("state", 0) == 1)

                if (isPluggedIn) {
                    val prefs = getSharedPreferences("player_playback_prefs", Context.MODE_PRIVATE)
                    val autoResume = prefs.getBoolean("bluetooth_auto_resume", false)
                    if (autoResume) {
                        player?.let { p ->
                            if (!p.isPlaying && p.mediaItemCount > 0) {
                                p.play()
                            }
                        }
                    }
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(Intent.ACTION_HEADSET_PLUG)
            }
            registerReceiver(bluetoothReceiver, filter)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupScreenListener() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON && isLockScreenEnabled) {
                    val ctx = context ?: return
                    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isLocked = km?.isKeyguardLocked == true
                    val isInteractive = pm?.isInteractive == true

                    // Solo abrir si el usuario encendió la pantalla interactivamente (no pulso ambiental de notificación),
                    // la música está reproduciéndose activamente y el dispositivo está bloqueado
                    player?.let { p ->
                        if (p.isPlaying && isLocked && isInteractive) {
                            launchIOSLockScreen()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun launchIOSLockScreen() {
        val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(lockIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun triggerSleepTimerStop() {
        player?.let { p ->
            if (SleepTimerManager.finishCurrentSong && p.isPlaying) {
                p.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        p.pause()
                        p.removeListener(this)
                    }
                })
            } else {
                p.pause()
            }
        }
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bluetoothReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}

/**
 * Gestor del temporizador de apagado automático (Sleep Timer) estilo BlackPlayer
 */
object SleepTimerManager {
    val remainingSeconds = MutableStateFlow<Long?>(null)
    var finishCurrentSong: Boolean = false
    private var timerJob: Job? = null

    fun startTimer(minutes: Int, finishSong: Boolean = false) {
        timerJob?.cancel()
        finishCurrentSong = finishSong
        val totalSeconds = minutes * 60L
        timerJob = CoroutineScope(Dispatchers.Default).launch {
            var current = totalSeconds
            while (current > 0) {
                remainingSeconds.value = current
                delay(1000)
                current--
            }
            remainingSeconds.value = 0
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.triggerSleepTimerStop()
            }
            remainingSeconds.value = null
            timerJob = null
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        remainingSeconds.value = null
    }

    fun formatRemaining(): String {
        val sec = remainingSeconds.value ?: return ""
        val minutes = sec / 60
        val seconds = sec % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
