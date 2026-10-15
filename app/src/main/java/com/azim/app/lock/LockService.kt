package com.azim.app.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.azim.app.MainActivity
import com.azim.app.ai.Reminders
import com.azim.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ön planda hangi uygulamanın açık olduğunu izler; kilit aktifken izinli olmayan bir uygulama
 * öne gelirse kilit ekranını (LockActivity) tekrar öne getirir.
 */
class LockService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    companion object {
        private const val CHANNEL = "azim_lock"
        private const val FG_EVENT = 1 // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LockService::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Azim disiplin modu", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Azim aktif")
            .setContentText("Disiplin modu çalışıyor")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, 1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notif)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job == null) job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var lastPkg: String? = null
        var lastCls: String? = null
        var since = System.currentTimeMillis() - 60 * 60 * 1000L
        var lastLaunch = 0L
        var loops = 0

        while (true) {
            // Yaklaşık 25 saniyede bir koç mesajı zamanı geldi mi diye bakar.
            if (loops++ % 40 == 0) {
                scope.launch { runCatching { Reminders.check(this@LockService) } }
            }
            runCatching {
                val now = System.currentTimeMillis()

                if (Perms.usageGranted(this)) {
                    val events = usm.queryEvents(since, now)
                    val e = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(e)
                        if (e.eventType == FG_EVENT) {
                            lastPkg = e.packageName
                            lastCls = e.className
                        }
                    }
                    since = now - 2_000
                }

                val state = LockPolicy.state(this)
                val pkg = lastPkg
                if (state != LockState.NONE && pkg != null && now - lastLaunch > 1_200) {
                    if (!AllowedApps.isAllowed(this, pkg, lastCls, state)) {
                        lastLaunch = now
                        if (state == LockState.SESSION) {
                            val label = runCatching {
                                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                            }.getOrDefault(pkg)
                            Prefs.get(this).addSessionAttempt(label)
                        }
                        startActivity(
                            Intent(this, LockActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            )
                        )
                    }
                }
            }
            delay(600)
        }
    }
}
