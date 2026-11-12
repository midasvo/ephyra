package dev.jdtech.jellyfin.core.presentation.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.core.R as CoreR
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that keeps the app process alive while the DownloadQueue has
 * work to do. The OkHttp download engine runs in-process, so closing the app would
 * stall any active or pending transfers until the user reopens the app. This service
 * keeps the process alive while work remains.
 *
 * CE (#560): the service does not decide by itself when it is done. It runs for as long as the
 * queue's pump is active ([DownloadQueue.pumpActive]) and its notification is only ever posted by
 * the running service: a queue that looked empty for a moment used to stop it while downloads still
 * waited for their retry, sometimes leaving a notification behind that nothing removed.
 */
@AndroidEntryPoint
class DownloadPumpService : Service() {

    @Inject lateinit var downloadQueue: DownloadQueue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    /** What the notification says now; a queue without work leaves it as it is. */
    private var notice: DownloadPumpNotice = DownloadPumpNotice.Downloading(1)

    /** The id of the last start delivered, for [stopSelfResult]. */
    private var lastStartId = 0

    /** Set once the service has stopped itself: nothing is posted after that. */
    private var stopped = false

    /**
     * True for a service created while the pump is idle. Only the system does that, when it starts
     * the service again after the process died: the queue restores its downloads a moment later, so
     * the service waits [RESTORE_GRACE_MS] for the pump before it concludes there is nothing to do.
     */
    private var awaitingRestore = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureNotificationChannel()
        downloadPumpNotice(downloadQueue.entries.value)?.let { notice = it }
        enterForeground()
        awaitingRestore = !downloadQueue.pumpActive.value
        if (awaitingRestore) {
            scope.launch {
                delay(RESTORE_GRACE_MS)
                awaitingRestore = false
                stopWhenPumpIdle()
            }
        }
        observeQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every start gets the foreground notification, not only the one that created the service:
        // a start that arrives while the service is on its way out (stopSelfResult below refused to
        // stop for it) still has to be answered with startForeground. CE (#372): this is also what
        // posts the notification again after the notification permission was granted.
        if (!stopped) enterForeground()
        // A start with nothing to pump: stop again.
        stopWhenPumpIdle()
        return START_STICKY
    }

    private fun observeQueue() {
        observeJob?.cancel()
        observeJob =
            scope.launch {
                combine(downloadQueue.pumpActive, downloadQueue.entries) { active, entries ->
                        active to entries
                    }
                    .collect { (active, entries) ->
                        if (stopped) return@collect
                        if (!active) {
                            stopWhenPumpIdle()
                            return@collect
                        }
                        awaitingRestore = false
                        val next = downloadPumpNotice(entries) ?: return@collect
                        if (next != notice) {
                            notice = next
                            getSystemService<NotificationManager>()
                                ?.notify(NOTIFICATION_ID, buildNotification())
                        }
                    }
            }
    }

    /**
     * Stops the service when the queue's pump is not active. [stopSelfResult] refuses when a newer
     * start is on its way (a download queued at this very moment): that start then finds the
     * service running and in the foreground again through [onStartCommand].
     */
    private fun stopWhenPumpIdle() {
        if (stopped || awaitingRestore || downloadQueue.pumpActive.value) return
        if (!stopSelfResult(lastStartId)) return
        stopped = true
        // Before anything else, so that no later queue update can post a notification (#560).
        observeJob?.cancel()
        stopForegroundCompat()
    }

    private fun enterForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+ refuses a foreground start from the background.
            Timber.e(e, "DownloadPumpService could not enter the foreground")
        }
    }

    private fun buildNotification(): android.app.Notification {
        val title =
            when (val notice = notice) {
                is DownloadPumpNotice.Downloading ->
                    resources.getQuantityString(
                        CoreR.plurals.downloads_in_progress_title,
                        notice.count,
                        notice.count,
                    )
                DownloadPumpNotice.WaitingForNetwork ->
                    getString(CoreR.string.download_waiting_for_network)
                DownloadPumpNotice.Waiting -> getString(CoreR.string.download_queued)
            }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_download)
            .setContentTitle(title)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(CoreR.string.downloads_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = getString(CoreR.string.downloads_channel_description)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ caps dataSync FGS at ~6h/day. Stop cleanly instead of letting
        // the system crash the process. In-flight OkHttp transfers will be interrupted;
        // partial files are kept and resumed next time the app opens (restoreAll + ensurePump).
        Timber.w("DownloadPumpService hit the dataSync time limit; stopping")
        stopped = true
        observeJob?.cancel()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        scope.cancel()
        // The notification belongs to the running service only (#560).
        getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val CHANNEL_ID = "download_pump"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_REFRESH_NOTIFICATION = "refresh_notification"
        private const val RESTORE_GRACE_MS = 5_000L

        @Volatile private var running = false

        fun start(context: Context) {
            val intent = Intent(context, DownloadPumpService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start DownloadPumpService")
            }
        }

        /**
         * CE (#372): posts the notification of a running service again, after the notification
         * permission was granted. Does nothing when the service is not running.
         */
        fun refreshNotification(context: Context) {
            if (!running) return
            val intent =
                Intent(context, DownloadPumpService::class.java)
                    .setAction(ACTION_REFRESH_NOTIFICATION)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh the DownloadPumpService notification")
            }
        }
    }
}
