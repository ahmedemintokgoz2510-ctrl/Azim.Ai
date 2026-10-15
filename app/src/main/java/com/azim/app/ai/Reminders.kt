package com.azim.app.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.azim.app.MainActivity
import com.azim.app.data.AppDb
import com.azim.app.data.ChatMsg
import com.azim.app.data.Prefs
import com.azim.app.lock.Perms
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * Günde 3 koç mesajı (sabah, öğle, akşam). LockService içindeki döngü düzenli olarak check() çağırır;
 * saati gelmiş ve bugün gönderilmemiş mesaj üretilir, sohbete yazılır ve bildirim olarak gösterilir.
 * Saatten sonraki 3 saat içinde servis açılırsa mesaj geç de olsa gönderilir.
 */
object Reminders {
    private const val CHANNEL = "azim_coach"
    private val mutex = Mutex()
    private val titles = listOf("Günaydın", "Gün ortası", "Akşam hesabı")

    suspend fun check(ctx: Context) {
        val prefs = Prefs.get(ctx)
        val uid = prefs.userId
        if (uid <= 0 || !prefs.remindersOn) return

        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        for (slot in 0..2) {
            val at = prefs.remMin(slot)
            if (nowMin < at || nowMin > at + 180) continue
            if (prefs.remSentToday(slot)) continue
            mutex.withLock {
                if (prefs.remSentToday(slot)) return@withLock
                prefs.markRemSent(slot)
                send(ctx, uid, slot)
            }
        }
    }

    private suspend fun send(ctx: Context, uid: Long, slot: Int) {
        runCatching { Repo.ensureTodayTasks(ctx, uid) }
        val text = try {
            Coach.reminder(ctx, uid, slot)
        } catch (e: Exception) {
            Coach.fallbackReminder(slot)
        }
        AppDb.get(ctx).dao().insertChat(
            ChatMsg(userId = uid, role = "model", text = text, ts = System.currentTimeMillis())
        )
        notify(ctx, slot, text)
    }

    private fun notify(ctx: Context, slot: Int, text: String) {
        if (!Perms.notificationsGranted(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Koç mesajları", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_chat", true)
        val pi = PendingIntent.getActivity(
            ctx, slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titles[slot])
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(100 + slot, n)
    }
}
