package com.mundymorning.touchreader.playback

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.mundymorning.touchreader.MainActivity
import com.mundymorning.touchreader.R
import com.mundymorning.touchreader.TouchReaderApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground media service: keeps narration alive with the screen off, shows the media
 * notification and routes headset / Bluetooth / lock-screen buttons to [PlaybackController].
 */
class PlaybackService : Service() {
    private lateinit var controller: PlaybackController
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { controller.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        controller = (application as TouchReaderApp).container.controller
        createChannel()
        session = MediaSessionCompat(this, "TouchReader").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = controller.play()
                override fun onPause() = controller.pause()
                override fun onSkipToNext() = controller.nextSentence()
                override fun onSkipToPrevious() = controller.previousSentence()
                override fun onFastForward() = controller.nextChapter()
                override fun onRewind() = controller.previousChapter()
                override fun onStop() = controller.pause()
            })
            isActive = true
        }
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        scope.launch { controller.ui.collectLatest { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.toggle()
            ACTION_NEXT -> controller.nextSentence()
            ACTION_PREV -> controller.previousSentence()
        }
        if (!foreground) enterForeground(buildNotification(controller.ui.value))
        if (controller.ui.value.book == null) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    private fun enterForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, n)
        foreground = true
    }

    private fun render(ui: PlayerUi) {
        val book = ui.book ?: return
        val playing = ui.playing
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, ui.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ui.chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book.author ?: "")
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) ui.speed else 0f,
                ).build()
        )
        updateWakeLock(playing)
        val n = buildNotification(ui)
        if (playing) {
            if (!foreground) enterForeground(n) else notify(n)
        } else {
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
            notify(n)
            if (ui.state == com.mundymorning.touchreader.core.tts.NarrationState.FINISHED) {
                stopSelf()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun notify(n: Notification) {
        try { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n) } catch (_: SecurityException) { }
    }

    private fun updateWakeLock(playing: Boolean) {
        if (playing && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TouchReader:narration").apply { acquire(6 * 60 * 60 * 1000L) }
        } else if (!playing) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun action(icon: Int, title: String, act: String, code: Int) = NotificationCompat.Action(
        icon, title,
        PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    )

    private fun buildNotification(ui: PlayerUi): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOC_ID, ui.docId ?: -1L).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(ui.title.ifEmpty { "Touch Reader" })
            .setContentText(ui.chapterTitle)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ui.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous sentence", ACTION_PREV, 1))
            .addAction(
                if (ui.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE, 2)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE, 2)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next sentence", ACTION_NEXT, 3))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.ui.value.playing) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        updateWakeLock(false)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "com.mundymorning.touchreader.TOGGLE"
        const val ACTION_NEXT = "com.mundymorning.touchreader.NEXT"
        const val ACTION_PREV = "com.mundymorning.touchreader.PREV"
    }
}
