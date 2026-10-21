package com.mundymorning.touchreader.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mundymorning.touchreader.MainActivity
import com.mundymorning.touchreader.R
import com.mundymorning.touchreader.TouchReaderApp

/**
 * Keeps audiobook rendering going with the screen off. Rendering is slow on some phones, so this is a long-lived
 * foreground service with a Stop button; stopping keeps everything that is finished.
 */
class ExportService : Service() {
    private lateinit var controller: ExportController
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        controller = (application as TouchReaderApp).container.exporter
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Audiobook rendering", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> controller.requestStop()
            ACTION_RUN -> if (worker?.isAlive != true) startWork()
        }
        return START_NOT_STICKY
    }

    private fun startWork() {
        val first = buildNotification(controller.status.value, ongoing = true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING_ID, first)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TouchReader:audiobook").apply { acquire(24 * 60 * 60 * 1000L) }
        worker = Thread({
            try {
                controller.runPending { st -> if (st.phase == ExportPhase.RENDERING) throttledNotify(st) else notifyOngoing(st) }
            } finally {
                main.post { finish() }
            }
        }, "tr-export").apply { isDaemon = true; start() }
    }

    private fun finish() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        val st = controller.status.value
        stopForeground(STOP_FOREGROUND_REMOVE)
        // a normal notification stays behind so the result is not missed
        notifyFinal(buildNotification(st, ongoing = false))
        stopSelf()
    }

    @Suppress("MissingPermission")
    private fun notifyOngoing(st: ExportStatus) {
        try { NotificationManagerCompat.from(this).notify(ONGOING_ID, buildNotification(st, ongoing = true)) } catch (_: SecurityException) { }
    }

    private fun throttledNotify(st: ExportStatus) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 2_000) return
        lastNotifyAt = now
        notifyOngoing(st)
    }

    @Suppress("MissingPermission")
    private fun notifyFinal(n: Notification) {
        try { NotificationManagerCompat.from(this).notify(DONE_ID, n) } catch (_: SecurityException) { }
    }

    private fun buildNotification(st: ExportStatus, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EXPORT_DOC_ID, st.docId ?: -1L).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = st.title.ifEmpty { "Audiobook" }
        val p = st.progress
        val text = when {
            st.phase == ExportPhase.RENDERING && p != null -> buildString {
                append("Chapter ${p.chaptersDone} of ${p.chaptersTotal} finished")
                p.etaSeconds?.let { append(" · about ${formatDuration(it)} left") }
            }
            else -> st.message ?: ""
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_reader)
            .setContentTitle(if (ongoing) "Making audiobook: $title" else when (st.phase) {
                ExportPhase.DONE -> "Audiobook ready: $title"
                ExportPhase.STOPPED -> "Audiobook paused: $title"
                else -> "Audiobook: $title"
            })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            if (p != null && p.chunksTotal > 0) b.setProgress(p.chunksTotal, p.chunksDone, false) else b.setProgress(0, 0, true)
            b.addAction(
                android.R.drawable.ic_media_pause, "Stop",
                PendingIntent.getService(this, 1, Intent(this, ExportService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        return b.build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RUN = "com.mundymorning.touchreader.EXPORT_RUN"
        const val ACTION_STOP = "com.mundymorning.touchreader.EXPORT_STOP"
        private const val CHANNEL = "export"
        private const val ONGOING_ID = 2
        private const val DONE_ID = 3
    }
}

/** "about 3 h 20 min" style text for estimates. */
fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h >= 10 -> "$h hours"
        h >= 1 -> "$h h ${m} min"
        m >= 1 -> "$m min"
        else -> "under a minute"
    }
}
