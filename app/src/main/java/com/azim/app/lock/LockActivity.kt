package com.azim.app.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azim.app.MainActivity
import com.azim.app.ai.Coach
import com.azim.app.ai.Repo
import com.azim.app.ai.TrainingTarget
import com.azim.app.data.AppDb
import com.azim.app.data.DailyTask
import com.azim.app.data.Dates
import com.azim.app.data.Prefs
import com.azim.app.ui.AzimTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LockActivity : ComponentActivity() {
    @Volatile
    private var locked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Kilit aktifken geri tuşu çalışmaz.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!locked) finish()
            }
        })

        setContent {
            AzimTheme {
                LockScreen(onLockedChange = { locked = it }, onClose = { finish() })
            }
        }
    }
}

private fun mmss(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun launchSafe(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun LockScreen(onLockedChange: (Boolean) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val uid = prefs.userId
    val today = Dates.today()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var state by remember { mutableStateOf(LockState.TIME) }
    var generating by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    var confirmEmergency by remember { mutableStateOf(false) }
    var quitOpen by remember { mutableStateOf(false) }

    val tasks by remember(uid, today) { dao.tasksFor(uid, today) }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            state = LockPolicy.state(ctx)
            onLockedChange(state != LockState.NONE)
            if (state == LockState.NONE) {
                onClose()
                break
            }
            delay(500)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp)
        ) {
            Text(
                if (state == LockState.SESSION) "Eğitim" else "Telefon kilitli",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))

            if (state == LockState.SESSION) {
                val endsAt = prefs.sessionEndsAt
                SessionView(
                    title = prefs.sessionTitle,
                    detail = prefs.sessionDetail,
                    intent = prefs.sessionIntent,
                    remainingMs = endsAt - now,
                    totalMs = (endsAt - prefs.sessionStartedAt).coerceAtLeast(1),
                    attempts = prefs.sessionAttempts,
                    lastBlocked = prefs.sessionLastBlocked,
                    onFinish = { note -> scope.launch { Repo.finishTraining(ctx, note.trim()) } },
                    onQuit = { quitOpen = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                TaskListView(
                    tasks = tasks,
                    generating = generating,
                    warning = warning,
                    onGenerate = {
                        scope.launch {
                            generating = true
                            warning = Repo.ensureTodayTasks(ctx, uid)
                            generating = false
                        }
                    },
                    onStart = { t ->
                        scope.launch {
                            Repo.startTraining(ctx, uid, TrainingTarget(t.id, t.goalId, t.title, t.detail), t.minutes, "")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))
            Shortcuts(state = state)
            Spacer(Modifier.height(4.dp))
            val left = prefs.emergencyLeft()
            TextButton(
                onClick = { confirmEmergency = true },
                enabled = left > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (left > 0) "Acil durum çıkışı ($left hak)" else "Acil durum hakkı kalmadı")
            }
        }
    }

    if (quitOpen && state == LockState.SESSION) {
        QuitDialog(
            title = prefs.sessionTitle,
            elapsedMin = ((now - prefs.sessionStartedAt) / 60_000).toInt().coerceAtLeast(0),
            remainingMin = ((prefs.sessionEndsAt - now) / 60_000).toInt().coerceAtLeast(0),
            attempts = prefs.sessionAttempts,
            intent = prefs.sessionIntent,
            onStay = { quitOpen = false },
            onQuit = {
                quitOpen = false
                scope.launch { Repo.endTraining(ctx, "quit") }
            }
        )
    }

    if (confirmEmergency) {
        AlertDialog(
            onDismissRequest = { confirmEmergency = false },
            title = { Text("Acil durum çıkışı") },
            text = {
                Text("Kilit 10 dakikalığına açılır ve aktif eğitim iptal olur. Bugünkü hakkın düşer. Gerçekten acil mi?")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmergency = false
                    scope.launch {
                        if (prefs.emergencyLeft() > 0) {
                            Repo.endTraining(ctx, "emergency")
                            prefs.useEmergency()
                        }
                    }
                }) { Text("Evet, aç") }
            },
            dismissButton = { TextButton(onClick = { confirmEmergency = false }) { Text("Vazgeç") } }
        )
    }
}

/** Bırakmak istediğinde önce koç konuşur; 10 saniye sonra "Yine de bırak" açılır. Karar kullanıcıdadır. */
@Composable
private fun QuitDialog(
    title: String,
    elapsedMin: Int,
    remainingMin: Int,
    attempts: Int,
    intent: String,
    onStay: () -> Unit,
    onQuit: () -> Unit
) {
    val ctx = LocalContext.current
    var advice by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableIntStateOf(10) }

    LaunchedEffect(Unit) {
        val prefs = Prefs.get(ctx)
        advice = withTimeoutOrNull(12_000) {
            runCatching {
                Coach.quitAdvice(ctx, prefs.userId, title, elapsedMin, remainingMin, attempts, intent)
            }.getOrNull()
        } ?: Coach.fallbackQuitAdvice(prefs.coachTone)
    }
    LaunchedEffect(Unit) {
        while (wait > 0) {
            delay(1000)
            wait -= 1
        }
    }

    AlertDialog(
        onDismissRequest = onStay,
        title = { Text("Bırakmadan önce") },
        text = {
            val a = advice
            if (a == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text("  Koç düşünüyor…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(a, style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = {
            TextButton(onClick = onQuit, enabled = wait == 0) {
                Text(if (wait > 0) "Yine de bırak ($wait)" else "Yine de bırak")
            }
        },
        dismissButton = { TextButton(onClick = onStay) { Text("Devam ediyorum") } }
    )
}

@Composable
private fun SessionView(
    title: String,
    detail: String,
    intent: String,
    remainingMs: Long,
    totalMs: Long,
    attempts: Int,
    lastBlocked: String,
    onFinish: (String) -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier
) {
    var note by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (intent.isNotBlank()) {
            Text("Niyetin: $intent", style = MaterialTheme.typography.bodyLarge)
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))

        if (remainingMs > 0) {
            Text(
                mmss(remainingMs),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            LinearProgressIndicator(
                progress = (1f - remainingMs.toFloat() / totalMs).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                "Mesajlar ve ek uygulamalar kapalı. Yalnızca telefon açık.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            if (attempts > 0) {
                Text(
                    "Bu eğitimde $attempts kez kaçmaya çalıştın" +
                        (if (lastBlocked.isNotBlank()) " (son: $lastBlocked)." else "."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            TextButton(onClick = onQuit, modifier = Modifier.fillMaxWidth()) {
                Text("Uygulamaları aç / eğitimi bırak")
            }
        } else {
            Text("Süre doldu. Ne yaptığını kısaca yaz ve eğitimi bitir.", style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Kısa not") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Button(
                onClick = { onFinish(note) },
                enabled = note.trim().length >= 3,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Eğitimi bitir") }
        }
    }
}

@Composable
private fun TaskListView(
    tasks: List<DailyTask>,
    generating: Boolean,
    warning: String?,
    onGenerate: () -> Unit,
    onStart: (DailyTask) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Bugünün görevlerini bitirmeden telefon açılmaz.",
            style = MaterialTheme.typography.titleMedium
        )
        warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (tasks.isEmpty()) {
            Text("Bugünün görevleri henüz oluşturulmadı.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onGenerate, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
                if (generating) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                else Text("Görevleri oluştur")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tasks.filter { !it.done }, key = { it.id }) { t ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            if (t.detail.isNotBlank()) {
                                Text(t.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${t.minutes} dk", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                                Button(onClick = { onStart(t) }) { Text("Başla") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shortcuts(state: LockState) {
    val ctx = LocalContext.current
    val buttons = remember(state) {
        val list = mutableListOf<Pair<String, Intent>>()
        list.add("Telefon" to Intent(Intent.ACTION_DIAL))
        if (state == LockState.TIME) {
            list.add("Mesajlar" to Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
            list.add("Azim" to Intent(ctx, MainActivity::class.java))
            val pm = ctx.packageManager
            for (pkg in Prefs.get(ctx).extraAllowed) {
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg)
                list.add(label to launch)
            }
        }
        list
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((label, intent) in buttons.take(3)) {
            FilledTonalButton(onClick = { launchSafe(ctx, Intent(intent)) }, modifier = Modifier.weight(1f)) {
                Text(label, maxLines = 1)
            }
        }
    }
    if (buttons.size > 3) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, intent) in buttons.drop(3).take(3)) {
                FilledTonalButton(onClick = { launchSafe(ctx, Intent(intent)) }, modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1)
                }
            }
        }
    }
}
