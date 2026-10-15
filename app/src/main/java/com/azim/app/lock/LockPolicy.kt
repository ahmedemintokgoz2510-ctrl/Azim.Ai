package com.azim.app.lock

import android.content.Context
import com.azim.app.data.AppDb
import com.azim.app.data.Dates
import com.azim.app.data.Prefs
import java.util.Calendar

enum class LockState { NONE, TIME, SESSION }

object LockPolicy {
    /**
     * SESSION: bir görev oturumu başlamış ve tamamlanmadı (bırakmaya izin yok).
     * TIME: kilit saati geldi ve bugünün görevleri bitmedi (ya da henüz oluşturulmadı).
     */
    suspend fun state(ctx: Context): LockState {
        val prefs = Prefs.get(ctx)
        val uid = prefs.userId
        if (uid <= 0) return LockState.NONE

        val now = System.currentTimeMillis()
        if (prefs.emergencyUntil > now) return LockState.NONE

        val dao = AppDb.get(ctx).dao()

        if (prefs.sessionActive) {
            val taskId = prefs.sessionTaskId
            val stale = taskId > 0 && dao.taskById(taskId).let { it == null || it.done }
            if (stale) prefs.clearSession() else return LockState.SESSION
        }

        prefs.applyPending()
        if (!prefs.lockEnabled) return LockState.NONE

        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minutes < prefs.lockStartMin) return LockState.NONE

        if (dao.goalsOnce(uid).isEmpty()) return LockState.NONE
        val today = Dates.today()
        val total = dao.totalCount(uid, today)
        val pending = dao.pendingCount(uid, today)
        return if (total == 0 || pending > 0) LockState.TIME else LockState.NONE
    }
}
