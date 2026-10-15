package com.azim.app.data

import android.content.Context
import com.azim.app.BuildConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Dates {
    fun today(): String = LocalDate.now().toString()

    fun trLong(): String =
        LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("tr")))

    fun fmtTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun fmtTs(ts: Long): String = SimpleDateFormat("d MMM HH:mm", Locale("tr")).format(Date(ts))

    fun fmtDay(ts: Long): String = SimpleDateFormat("d MMMM yyyy", Locale("tr")).format(Date(ts))

    fun shortDay(iso: String): String =
        LocalDate.parse(iso).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale("tr"))
}

data class Profile(val state: String = "", val vision: String = "", val obstacles: String = "")

/** Uygulama ayarları ve oturum/kilit durumu. Tek bir SharedPreferences dosyasında tutulur. */
class Prefs private constructor(context: Context) {
    private val sp = context.getSharedPreferences("azim", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }

        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    // --- Hesap ---
    var userId: Long
        get() = sp.getLong("userId", 0L)
        set(v) { sp.edit().putLong("userId", v).apply() }

    // --- Yapay zeka anahtarları (en fazla 4, sırayla kullanılır) ---
    private fun savedKeyList(): List<String> {
        val multi = (sp.getString("apiKeys", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (multi.isNotEmpty()) return multi
        val legacy = (sp.getString("apiKey", "") ?: "").trim()
        return if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
    }

    /** Kullanılacak anahtarlar; hiç girilmediyse derlemeye gömülü varsayılan. */
    fun keys(): List<String> {
        val saved = savedKeyList()
        val list = if (saved.isNotEmpty()) saved else listOf(BuildConfig.DEFAULT_GEMINI_KEY).filter { it.isNotBlank() }
        return list.distinct().take(4)
    }

    fun hasKeys(): Boolean = keys().isNotEmpty()

    /** Ayar ekranı için her zaman 4 alan. */
    fun editableKeys(): List<String> = (savedKeyList() + List(4) { "" }).take(4)

    fun setKeys(list: List<String>) {
        sp.edit()
            .putString("apiKeys", list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4).joinToString("\n"))
            .remove("apiKey")
            .apply()
    }

    private fun keyId(key: String) = Integer.toHexString(key.hashCode())

    fun keyUseToday(key: String): Int = sp.getInt("use_${keyId(key)}_${Dates.today()}", 0)

    fun addKeyUse(key: String) {
        val k = "use_${keyId(key)}_${Dates.today()}"
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply()
    }

    // --- Koç tonu: 1 nazik, 2 net, 3 sert ---
    var coachTone: Int
        get() = sp.getInt("coachTone", 3)
        set(v) { sp.edit().putInt("coachTone", v.coerceIn(1, 3)).apply() }

    // --- Günde 3 koç mesajı (sabah, öğle, akşam) ---
    var remindersOn: Boolean
        get() = sp.getBoolean("remindersOn", true)
        set(v) { sp.edit().putBoolean("remindersOn", v).apply() }

    fun remMin(slot: Int): Int = sp.getInt("remMin_$slot", intArrayOf(8 * 60, 14 * 60, 21 * 60)[slot])
    fun setRemMin(slot: Int, minutes: Int) { sp.edit().putInt("remMin_$slot", minutes).apply() }
    fun remSentToday(slot: Int): Boolean = sp.getString("remSent_$slot", "") == Dates.today()
    fun markRemSent(slot: Int) { sp.edit().putString("remSent_$slot", Dates.today()).apply() }

    // --- Başlangıç profili ---
    fun profile(uid: Long) = Profile(
        state = sp.getString("p_state_$uid", "") ?: "",
        vision = sp.getString("p_vision_$uid", "") ?: "",
        obstacles = sp.getString("p_obst_$uid", "") ?: ""
    )

    fun saveProfile(uid: Long, p: Profile) {
        sp.edit()
            .putString("p_state_$uid", p.state.trim())
            .putString("p_vision_$uid", p.vision.trim())
            .putString("p_obst_$uid", p.obstacles.trim())
            .putBoolean("p_done_$uid", true)
            .apply()
    }

    fun profileDone(uid: Long): Boolean = sp.getBoolean("p_done_$uid", false)
    fun markProfileDone(uid: Long) { sp.edit().putBoolean("p_done_$uid", true).apply() }

    var photoConsent: Boolean
        get() = sp.getBoolean("photoConsent", false)
        set(v) { sp.edit().putBoolean("photoConsent", v).apply() }

    // --- Kilit yapılandırması ---
    var lockEnabled: Boolean
        get() = sp.getBoolean("lockEnabled", true)
        private set(v) { sp.edit().putBoolean("lockEnabled", v).apply() }

    /** Günün kaçıncı dakikasında kilit başlar (varsayılan 20:00). */
    var lockStartMin: Int
        get() = sp.getInt("lockStartMin", 20 * 60)
        private set(v) { sp.edit().putInt("lockStartMin", v).apply() }

    private var pendingAt: Long
        get() = sp.getLong("pendingAt", 0L)
        set(v) { sp.edit().putLong("pendingAt", v).apply() }
    private var pendingEnabled: Boolean
        get() = sp.getBoolean("pendingEnabled", true)
        set(v) { sp.edit().putBoolean("pendingEnabled", v).apply() }
    private var pendingStart: Int
        get() = sp.getInt("pendingStart", 20 * 60)
        set(v) { sp.edit().putInt("pendingStart", v).apply() }

    /** Bekleyen (24 saat gecikmeli) bir kilit değişikliği varsa süresi dolduğunda uygular. */
    fun applyPending() {
        val at = pendingAt
        if (at > 0 && at <= System.currentTimeMillis()) {
            lockEnabled = pendingEnabled
            lockStartMin = pendingStart
            pendingAt = 0
        }
    }

    fun hasPending(): Boolean = pendingAt > System.currentTimeMillis()
    fun pendingApplyAt(): Long = pendingAt

    /** Ekranda gösterilecek (talep edilen) değerler. */
    fun shownEnabled(): Boolean { applyPending(); return if (hasPending()) pendingEnabled else lockEnabled }
    fun shownStart(): Int { applyPending(); return if (hasPending()) pendingStart else lockStartMin }

    /**
     * Kilidi güçlendiren değişiklikler hemen, zayıflatanlar (kapatma, saati geciktirme)
     * 24 saat sonra uygulanır. true dönerse değişiklik beklemeye alındı.
     */
    fun requestLockChange(enabled: Boolean, startMin: Int): Boolean {
        applyPending()
        val weaker = (lockEnabled && !enabled) || (lockEnabled && startMin > lockStartMin)
        return if (weaker) {
            pendingEnabled = enabled
            pendingStart = startMin
            pendingAt = System.currentTimeMillis() + DAY_MS
            true
        } else {
            lockEnabled = enabled
            lockStartMin = startMin
            pendingAt = 0
            false
        }
    }

    // --- Acil durum çıkışı ---
    var emergencyPerDay: Int
        get() = sp.getInt("emergencyPerDay", 1)
        set(v) { sp.edit().putInt("emergencyPerDay", v.coerceIn(0, 2)).apply() }

    private var emergencyDate: String
        get() = sp.getString("emergencyDate", "") ?: ""
        set(v) { sp.edit().putString("emergencyDate", v).apply() }
    private var emergencyCount: Int
        get() = sp.getInt("emergencyCount", 0)
        set(v) { sp.edit().putInt("emergencyCount", v).apply() }

    var emergencyUntil: Long
        get() = sp.getLong("emergencyUntil", 0L)
        private set(v) { sp.edit().putLong("emergencyUntil", v).apply() }

    fun emergencyLeft(): Int {
        if (emergencyDate != Dates.today()) return emergencyPerDay
        return (emergencyPerDay - emergencyCount).coerceAtLeast(0)
    }

    /** 10 dakikalık acil çıkış; aktif oturumu da iptal eder. */
    fun useEmergency(): Boolean {
        if (emergencyLeft() <= 0) return false
        val today = Dates.today()
        if (emergencyDate != today) { emergencyDate = today; emergencyCount = 0 }
        emergencyCount += 1
        emergencyUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        clearSession()
        return true
    }

    // --- Eğitim (odak) oturumu ---
    val sessionActive: Boolean get() = sp.getLong("sessionStartedAt", 0L) > 0L
    val sessionStartedAt: Long get() = sp.getLong("sessionStartedAt", 0L)
    val sessionEndsAt: Long get() = sp.getLong("sessionEndsAt", 0L)
    /** 0 ise bir göreve bağlı olmayan serbest eğitim. */
    val sessionTaskId: Long get() = sp.getLong("sessionTaskId", 0L)
    val sessionTrainingId: Long get() = sp.getLong("sessionTrainingId", 0L)
    val sessionTitle: String get() = sp.getString("sessionTitle", "") ?: ""
    val sessionDetail: String get() = sp.getString("sessionDetail", "") ?: ""
    val sessionIntent: String get() = sp.getString("sessionIntent", "") ?: ""
    val sessionAttempts: Int get() = sp.getInt("sessionAttempts", 0)
    val sessionLastBlocked: String get() = sp.getString("sessionLastBlocked", "") ?: ""

    fun startSession(taskId: Long, minutes: Int, trainingId: Long, title: String, detail: String, intent: String) {
        val now = System.currentTimeMillis()
        sp.edit()
            .putLong("sessionTaskId", taskId)
            .putLong("sessionTrainingId", trainingId)
            .putLong("sessionStartedAt", now)
            .putLong("sessionEndsAt", now + minutes * 60_000L)
            .putString("sessionTitle", title)
            .putString("sessionDetail", detail)
            .putString("sessionIntent", intent)
            .putInt("sessionAttempts", 0)
            .putString("sessionLastBlocked", "")
            .apply()
    }

    /** Eğitim sırasında kapalı bir uygulamayı açma girişimi. */
    fun addSessionAttempt(label: String) {
        sp.edit().putInt("sessionAttempts", sessionAttempts + 1).putString("sessionLastBlocked", label).apply()
    }

    fun clearSession() {
        sp.edit()
            .remove("sessionTaskId").remove("sessionTrainingId").remove("sessionStartedAt").remove("sessionEndsAt")
            .remove("sessionTitle").remove("sessionDetail").remove("sessionIntent")
            .remove("sessionAttempts").remove("sessionLastBlocked")
            .apply()
    }

    // --- Kilitteyken açık kalacak ek uygulamalar ---
    var extraAllowed: Set<String>
        get() = sp.getStringSet("extraAllowed", emptySet())?.toSet() ?: emptySet()
        set(v) { sp.edit().putStringSet("extraAllowed", v.toSet()).apply() }
}
