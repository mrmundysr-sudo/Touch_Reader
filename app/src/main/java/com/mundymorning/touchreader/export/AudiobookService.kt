package com.mundymorning.touchreader.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.mundymorning.touchreader.MainActivity
import com.mundymorning.touchreader.R
import com.mundymorning.touchreader.TouchReaderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps an audiobook playing with the screen off, with a media notification and headset / lock-screen controls. */
class AudiobookService : Service() {
    private lateinit var player: AudiobookPlayer
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { player.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        player = (application as TouchReaderApp).container.audiobook
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Audiobook playback", NotificationManager.IMPORTANCE_LOW))
        }
        session = MediaSessionCompat(this, "TouchReaderAudiobook").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.play()
                override fun onPause() = player.pause()
                override fun onSkipToNext() = player.next()
                override fun onSkipToPrevious() = player.previous()
                override fun onFastForward() = player.skip(30)
                override fun onRewind() = player.skip(-15)
                override fun onStop() = player.pause()
            })
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        scope.launch { player.state.collectLatest { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_BACK -> player.skip(-15)
            ACTION_FORWARD -> player.skip(30)
        }
        if (!foreground) enterForeground(buildNotification(player.state.value))
        if (player.state.value.chapters.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    private fun enterForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        foreground = true
    }

    private fun render(st: AudiobookState) {
        if (st.chapters.isEmpty()) return
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, st.current?.title.orEmpty())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, st.durationMs.toLong())
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_STOP
                )
                .setState(if (st.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, st.positionMs.toLong(), if (st.playing) st.speed else 0f)
                .build()
        )
        val n = buildNotification(st)
        if (st.playing) { if (!foreground) enterForeground(n) else notify(n) }
        else {
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
            notify(n)
        }
    }

    @Suppress("MissingPermission")
    private fun notify(n: Notification) {
        try { NotificationManagerCompat.from(this).notify(ID, n) } catch (_: SecurityException) { }
    }

    private fun action(icon: Int, title: String, act: String, code: Int) = NotificationCompat.Action(
        icon, title,
        PendingIntent.getService(this, 10 + code, Intent(this, AudiobookService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    )

    private fun buildNotification(st: AudiobookState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EXPORT_DOC_ID, st.docId ?: -1L).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(st.title.ifEmpty { "Audiobook" })
            .setContentText(st.current?.title.orEmpty())
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(st.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_rew, "Back 15 seconds", ACTION_BACK, 1))
            .addAction(
                if (st.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE, 2)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE, 2)
            )
            .addAction(action(android.R.drawable.ic_media_ff, "Forward 30 seconds", ACTION_FORWARD, 3))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.state.value.playing) stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "audiobook"
        private const val ID = 4
        const val ACTION_TOGGLE = "com.mundymorning.touchreader.AB_TOGGLE"
        const val ACTION_BACK = "com.mundymorning.touchreader.AB_BACK"
        const val ACTION_FORWARD = "com.mundymorning.touchreader.AB_FORWARD"
    }
}
