package com.azim.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azim.app.AppViewModel
import com.azim.app.data.DailyTask
import com.azim.app.data.Dates
import com.azim.app.lock.LockActivity
import com.azim.app.lock.Perms

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onAddGoal: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProgress: () -> Unit
) {
    val ctx = LocalContext.current
    val user by vm.user.collectAsState()
    val goals by vm.goals.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val stats by vm.stats.collectAsState()
    val busy by vm.busy.collectAsState()
    val reports by vm.reports.collectAsState()
    val tick by rememberResumeTick()

    val missing = remember(tick) { Perms.missingCritical(ctx) }
    val sessionId = remember(tick, tasks) { vm.prefs.sessionTaskId }
    val sessionActive = remember(tick, tasks) { vm.prefs.sessionActive }
    var showTraining by remember { mutableStateOf(false) }
    var preselect by remember { mutableLongStateOf(0L) }

    LaunchedEffect(user?.id, goals?.size) { vm.ensureToday() }

    val doneCount = tasks.count { it.done }
    val weeklyBase = reports.firstOrNull()?.createdAt ?: goals?.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
    val weeklyDue = System.currentTimeMillis() - weeklyBase >= 7L * 24 * 60 * 60 * 1000

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Merhaba, ${user?.name ?: ""}", style = MaterialTheme.typography.headlineMedium)
                Text(Dates.trLong(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Bugün $doneCount / ${tasks.size} görev", style = MaterialTheme.typography.titleMedium)
                        Text("Seri: ${stats.streak} gün", style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(
                        progress = if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Eğitim modu", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Konu ve süre seç. Süre boyunca mesajlar ve ek uygulamalar kapanır; yalnızca telefon açık kalır.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sessionActive) {
                        Button(onClick = { ctx.startActivity(Intent(ctx, LockActivity::class.java)) }) { Text("Eğitime dön") }
                    } else {
                        Button(onClick = { preselect = 0L; showTraining = true }) { Text("Eğitime geç") }
                    }
                }
            }
        }

        if (missing.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Kilit henüz çalışmıyor", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            "Eksik izinler: ${missing.joinToString(", ")}. Ayarlar sekmesinden ver.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        OutlinedButton(onClick = onOpenSettings) { Text("Ayarlar'a git") }
                    }
                }
            }
        }

        if (weeklyDue) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Haftalık fotoğraf zamanı", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Bu haftaki gelişimini bir fotoğrafla kaydet; koçun değerlendirsin.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(onClick = onOpenProgress) { Text("İlerleme sekmesine git") }
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bugünün görevleri hazırlanıyor…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busy) CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                    else OutlinedButton(onClick = { vm.ensureToday() }) { Text("Görevleri oluştur") }
                }
            }
        }

        items(tasks, key = { it.id }) { t ->
            TaskCard(
                task = t,
                sessionId = sessionId,
                sessionActive = sessionActive,
                onStart = { preselect = t.id; showTraining = true },
                onResume = { ctx.startActivity(Intent(ctx, LockActivity::class.java)) }
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("Hedeflerin", style = MaterialTheme.typography.titleMedium)
        }
        items(goals ?: emptyList(), key = { it.id }) { g ->
            Column(Modifier.fillMaxWidth()) {
                Text(g.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${g.category} · ${g.level} · günde ${g.dailyMinutes} dk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            OutlinedButton(onClick = onAddGoal, modifier = Modifier.fillMaxWidth()) { Text("Yeni hedef ekle") }
        }
    }

    if (showTraining) {
        TrainingDialog(
            tasks = tasks,
            goals = goals ?: emptyList(),
            preselectTaskId = preselect,
            onDismiss = { showTraining = false },
            onStart = { target, minutes, intent ->
                showTraining = false
                vm.startTraining(target, minutes, intent) {
                    ctx.startActivity(Intent(ctx, LockActivity::class.java))
                }
            }
        )
    }
}

@Composable
private fun TaskCard(task: DailyTask, sessionId: Long, sessionActive: Boolean, onStart: () -> Unit, onResume: () -> Unit) {
    val container = if (task.done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            if (task.detail.isNotBlank()) {
                Text(task.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.done && task.note.isNotBlank()) {
                Text("Not: ${task.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${task.minutes} dk", style = MaterialTheme.typography.labelLarge)
                when {
                    task.done -> Text("Tamamlandı", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    sessionActive && sessionId == task.id -> Button(onClick = onResume) { Text("Eğitime dön") }
                    else -> Button(onClick = onStart, enabled = !sessionActive) { Text("Başla") }
                }
            }
        }
    }
}
