package com.fileslicer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

class SplitService : Service() {

    companion object {
        const val ACTION_START = "com.fileslicer.START"
        const val ACTION_STOP = "com.fileslicer.STOP"
        private const val CH = "fileslicer_work"
        private const val NID = 11
        private const val NID_DONE = 12

        fun start(ctx: Context, job: Job) {
            val i = Intent(ctx, SplitService::class.java).setAction(ACTION_START)
            Store.put(i, job)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private lateinit var nm: NotificationManager
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "File work", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Control.cancel = true
            if (worker?.isAlive != true) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        // intent == null -> Android restarted us after killing the process: auto-resume
        val job = if (intent == null) Store.load(this) else Store.get(intent)
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground(job)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_STICKY
        begin(job)
        return START_STICKY
    }

    private fun openPi(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun notif(title: String, text: String, pct: Int, indeterminate: Boolean): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SplitService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPi())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stopPi
                ).build()
            )
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun goForeground(job: Job): Boolean {
        val n = notif(job.title(), "Starting…", 0, true)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NID, n)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun begin(job: Job) {
        Control.cancel = false
        Store.save(this, job)
        JobState.flow.value = UiJob(running = true, kind = job.kind, title = job.title())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fileslicer:work").apply {
            setReferenceCounted(false)
            acquire(6L * 60 * 60 * 1000)
        }

        worker = Thread {
            var t0 = SystemClock.elapsedRealtime()
            var baseDone = -1L
            var lastUi = 0L
            var lastDone = 0L
            var lastTotal = 0L
            var curPhase = ""

            val phase: (String) -> Unit = { p ->
                curPhase = p
                baseDone = -1L
                lastUi = 0L
                t0 = SystemClock.elapsedRealtime()
                JobState.flow.value = JobState.flow.value.copy(phase = p, speed = 0L)
                nm.notify(NID, notif(job.title(), p, 0, true))
            }

            val progress: (Long, Long) -> Unit = { done, total ->
                lastDone = done
                lastTotal = total
                val now = SystemClock.elapsedRealtime()
                if (baseDone < 0) baseDone = done
                if (now - lastUi >= 400 || done >= total) {
                    lastUi = now
                    val el = maxOf(1L, now - t0)
                    val speed = (done - baseDone) * 1000 / el
                    JobState.flow.value = UiJob(
                        running = true, kind = job.kind, title = job.title(),
                        done = done, total = total, speed = speed, phase = curPhase
                    )
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    nm.notify(
                        NID,
                        notif(
                            job.title(),
                            curPhase + "  " + pct + "%  •  " + fmtSize(done) + " / " + fmtSize(total),
                            pct, false
                        )
                    )
                }
            }

            var result: String
            var isErr = false
            try {
                if (job.kind == "split") {
                    val out = Engine.split(this@SplitService, job, { Control.cancel }, progress, phase)
                    val n = Engine.readInfo(out)?.parts ?: 0
                    result = "Done — $n parts saved in ${out.path}"
                } else {
                    val out = Engine.merge(job, { Control.cancel }, progress, phase)
                    result = "Done — saved to ${out.path}"
                }
            } catch (c: Cancelled) {
                result = "Stopped. You can resume it any time."
            } catch (t: Throwable) {
                result = "Error: ${t.message ?: t.javaClass.simpleName}. You can resume it."
                isErr = true
            }

            JobState.flow.value = UiJob(
                running = false, kind = job.kind, title = job.title(),
                done = lastDone, total = lastTotal, result = result, error = isErr
            )
            Store.clear(this@SplitService)
            val msg = result
            Handler(Looper.getMainLooper()).post { finishUp(job, msg) }
        }
        worker?.start()
    }

    private fun finishUp(job: Job, result: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(job.title())
            .setContentText(result)
            .setContentIntent(openPi())
            .setAutoCancel(true)
            .build()
        nm.notify(NID_DONE, n)
        releaseWake()
        stopSelf()
    }

    private fun releaseWake() {
        try {
            wake?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wake = null
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }
}
