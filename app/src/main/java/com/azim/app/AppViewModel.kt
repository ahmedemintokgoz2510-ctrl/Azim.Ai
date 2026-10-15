package com.azim.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azim.app.ai.Coach
import com.azim.app.ai.Repo
import com.azim.app.ai.TrainingTarget
import com.azim.app.data.AppDb
import com.azim.app.data.Auth
import com.azim.app.data.ChatMsg
import com.azim.app.data.DailyTask
import com.azim.app.data.Dates
import com.azim.app.data.Goal
import com.azim.app.data.Prefs
import com.azim.app.data.User
import com.azim.app.data.WeeklyReport
import com.azim.app.lock.LockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class DayStat(val date: String, val done: Int, val total: Int)

data class Stats(
    val streak: Int = 0,
    val week: List<DayStat> = emptyList(),
    val trainingMinutes: Int = 0
) {
    val weekDone: Int get() = week.sumOf { it.done }
    val weekTotal: Int get() = week.sumOf { it.total }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val dao = AppDb.get(ctx).dao()
    val prefs: Prefs = Prefs.get(ctx)

    val ready = MutableStateFlow(false)
    val user = MutableStateFlow<User?>(null)
    val authError = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val chatBusy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val stats = MutableStateFlow(Stats())
    val openChat = MutableStateFlow(false)
    val editingProfile = MutableStateFlow(false)
    val profileVersion = MutableStateFlow(0)

    fun saveProfile(p: com.azim.app.data.Profile) {
        val u = user.value ?: return
        prefs.saveProfile(u.id, p)
        profileVersion.value += 1
        editingProfile.value = false
    }

    fun skipProfile() {
        val u = user.value ?: return
        prefs.markProfileDone(u.id)
        profileVersion.value += 1
        editingProfile.value = false
    }
    private val todayKey = MutableStateFlow(Dates.today())

    val goals: StateFlow<List<Goal>?> = user.flatMapLatest { u ->
        if (u == null) flowOf<List<Goal>?>(null) else dao.goals(u.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tasks: StateFlow<List<DailyTask>> = combine(user, todayKey) { u, d -> u to d }
        .flatMapLatest { (u, d) -> if (u == null) flowOf(emptyList<DailyTask>()) else dao.tasksFor(u.id, d) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val chat: StateFlow<List<ChatMsg>> = user
        .flatMapLatest { u -> if (u == null) flowOf(emptyList<ChatMsg>()) else dao.chat(u.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val reports: StateFlow<List<WeeklyReport>> = user
        .flatMapLatest { u -> if (u == null) flowOf(emptyList<WeeklyReport>()) else dao.reports(u.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trainings: StateFlow<List<com.azim.app.data.Training>> = user
        .flatMapLatest { u ->
            if (u == null) flowOf(emptyList<com.azim.app.data.Training>()) else dao.recentTrainings(u.id, 8)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val id = prefs.userId
            if (id > 0) {
                val u = dao.userById(id)
                if (u == null) prefs.userId = 0 else {
                    user.value = u
                    LockService.start(ctx)
                    refreshStats()
                }
            }
            ready.value = true
        }
    }

    fun onResume() {
        todayKey.value = Dates.today()
        viewModelScope.launch { refreshStats() }
    }

    // ---------- Hesap ----------
    fun register(name: String, email: String, password: String) {
        viewModelScope.launch {
            authError.value = null
            val e = email.trim().lowercase()
            if (name.isBlank()) { authError.value = "Adını yaz."; return@launch }
            if (!e.contains("@") || !e.contains(".")) { authError.value = "Geçerli bir e-posta gir."; return@launch }
            if (password.length < 6) { authError.value = "Parola en az 6 karakter olmalı."; return@launch }
            if (dao.userByEmail(e) != null) { authError.value = "Bu e-posta ile zaten bir hesap var."; return@launch }
            val salt = Auth.newSalt()
            val hash = withContext(Dispatchers.Default) { Auth.hash(password, salt) }
            val u = User(name = name.trim(), email = e, salt = Auth.encode(salt), hash = hash)
            val id = dao.insertUser(u)
            signIn(u.copy(id = id))
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            authError.value = null
            val u = dao.userByEmail(email.trim().lowercase())
            val ok = u != null && withContext(Dispatchers.Default) { Auth.verify(password, u) }
            if (!ok || u == null) { authError.value = "E-posta ya da parola hatalı."; return@launch }
            signIn(u)
        }
    }

    private suspend fun signIn(u: User) {
        prefs.userId = u.id
        user.value = u
        LockService.start(ctx)
        refreshStats()
    }

    fun logout() {
        val uid = prefs.userId
        prefs.userId = 0
        prefs.clearSession()
        user.value = null
        stats.value = Stats()
        if (uid > 0) viewModelScope.launch { dao.closeActiveTrainings(uid, System.currentTimeMillis()) }
    }

    // ---------- Hedefler ve görevler ----------
    fun addGoal(title: String, category: String, level: String, minutes: Int, onDone: () -> Unit) {
        val u = user.value ?: return
        viewModelScope.launch {
            dao.insertGoal(
                Goal(
                    userId = u.id, title = title.trim(), category = category, level = level,
                    dailyMinutes = minutes, createdAt = System.currentTimeMillis()
                )
            )
            onDone()
            ensureToday()
        }
    }

    fun ensureToday() {
        val u = user.value ?: return
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val warning = Repo.ensureTodayTasks(ctx, u.id)
            busy.value = false
            if (warning != null) messages.tryEmit(warning)
            refreshStats()
        }
    }

    fun startTraining(target: TrainingTarget, minutes: Int, intent: String, onStarted: () -> Unit) {
        val u = user.value ?: return
        viewModelScope.launch {
            Repo.startTraining(ctx, u.id, target, minutes, intent.trim())
            onStarted()
        }
    }

    suspend fun refreshStats() {
        val u = user.value ?: return
        val dates = dao.fullyDoneDates(u.id).toSet()
        var d = LocalDate.now()
        if (d.toString() !in dates) d = d.minusDays(1)
        var streak = 0
        while (d.toString() in dates) { streak++; d = d.minusDays(1) }

        val from = LocalDate.now().minusDays(6)
        val rows = dao.tasksSince(u.id, from.toString())
        val week = (0..6).map { i ->
            val day = from.plusDays(i.toLong()).toString()
            val list = rows.filter { it.date == day }
            DayStat(day, list.count { it.done }, list.size)
        }
        stats.value = Stats(streak, week, dao.trainedMinutes(u.id))
    }

    // ---------- Koç sohbeti ----------
    fun sendChat(text: String) {
        val u = user.value ?: return
        val t = text.trim()
        if (t.isEmpty() || chatBusy.value) return
        viewModelScope.launch {
            chatBusy.value = true
            val history = dao.recentChat(u.id, 14).reversed().map { it.role to it.text }
            dao.insertChat(ChatMsg(userId = u.id, role = "user", text = t, ts = System.currentTimeMillis()))
            try {
                val reply = Coach.chat(ctx, u.id, history, t)
                dao.insertChat(ChatMsg(userId = u.id, role = "model", text = reply, ts = System.currentTimeMillis()))
            } catch (e: Exception) {
                messages.tryEmit("Yanıt alınamadı: ${e.message}")
            }
            chatBusy.value = false
        }
    }

    // ---------- Haftalık fotoğraf ----------
    fun submitWeekly(goal: Goal, photo: File) {
        val u = user.value ?: return
        viewModelScope.launch {
            busy.value = true
            try {
                val prev = reports.value.firstOrNull { it.goalId == goal.id }?.feedback
                val feedback = Coach.analyzePhoto(ctx, u.id, goal, photo, prev)
                val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
                val saved = File(dir, "w_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) { photo.copyTo(saved, overwrite = true) }
                dao.insertReport(
                    WeeklyReport(
                        userId = u.id, goalId = goal.id, createdAt = System.currentTimeMillis(),
                        photoPath = saved.path, feedback = feedback
                    )
                )
            } catch (e: Exception) {
                messages.tryEmit("Analiz yapılamadı: ${e.message}")
            } finally {
                photo.delete()
                busy.value = false
            }
        }
    }
}
