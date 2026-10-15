package com.azim.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.azim.app.AppViewModel
import com.azim.app.data.Dates
import com.azim.app.data.WeeklyReport
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val stats by vm.stats.collectAsState()
    val goals by vm.goals.collectAsState()
    val reports by vm.reports.collectAsState()
    val busy by vm.busy.collectAsState()

    val trainings by vm.trainings.collectAsState()

    var selectedGoalId by remember { mutableLongStateOf(0L) }
    var showConsent by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }

    val goalList = goals ?: emptyList()
    val goal = goalList.firstOrNull { it.id == selectedGoalId } ?: goalList.firstOrNull()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingFile
        if (ok && f != null && goal != null) vm.submitWeekly(goal, f) else f?.delete()
        pendingFile = null
    }

    fun launchCamera() {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        pendingFile = f
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        camera.launch(uri)
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("İlerleme", style = MaterialTheme.typography.headlineMedium) }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Seri", "${stats.streak} gün")
                        Stat("Bu hafta", "${stats.weekDone}/${stats.weekTotal}")
                        Stat("Eğitim", "${stats.trainingMinutes / 60} sa ${stats.trainingMinutes % 60} dk")
                    }
                    WeekBars(stats.week.map { Triple(Dates.shortDay(it.date), it.done, it.total) })
                }
            }
        }

        if (trainings.isNotEmpty()) {
            item { Text("Son eğitimler", style = MaterialTheme.typography.titleLarge) }
            items(trainings, key = { "tr${it.id}" }) { t -> TrainingRow(t) }
        }

        item { Text("Haftalık fotoğraf", style = MaterialTheme.typography.titleLarge) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Haftada bir fotoğraf çek (çalışma masan, defterin, duruşun, ilerleme kanıtın). Koçun görsele ve haftalık performansına bakıp geri bildirim verir.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (goalList.size > 1) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (g in goalList) {
                            FilterChip(
                                selected = goal?.id == g.id,
                                onClick = { selectedGoalId = g.id },
                                label = { Text(g.title.take(24)) }
                            )
                        }
                    }
                }
                Button(
                    onClick = { if (vm.prefs.photoConsent) launchCamera() else showConsent = true },
                    enabled = goal != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Fotoğraf çek ve analiz ettir")
                }
            }
        }

        items(reports, key = { it.id }) { r -> ReportCard(r, goalList.firstOrNull { it.id == r.goalId }?.title ?: "") }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Fotoğraf Google'a gönderilir") },
            text = {
                Text(
                    "Analiz için fotoğraf, Gemini API üzerinden Google'a gönderilir. Ücretsiz API katmanında Google gönderilen içeriği ürünlerini geliştirmek için kullanabilir. Yüzünün ya da özel bilgilerin göründüğü fotoğrafları göndermemeni öneririz."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.prefs.photoConsent = true
                    showConsent = false
                    launchCamera()
                }) { Text("Anladım, devam") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Son 7 günün tamamlanma oranını gösteren sade çubuklar. */
@Composable
private fun WeekBars(days: List<Triple<String, Int, Int>>) {
    Row(
        Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        for ((label, done, total) in days) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val ratio = if (total == 0) 0f else done.toFloat() / total
                Box(
                    Modifier
                        .size(width = 26.dp, height = 64.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((64 * ratio).dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReportCard(r: WeeklyReport, goalTitle: String) {
    val bmp = remember(r.photoPath) {
        runCatching {
            BitmapFactory.decodeFile(r.photoPath, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }.getOrNull()
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Dates.fmtDay(r.createdAt) + if (goalTitle.isNotBlank()) " · $goalTitle" else "", style = MaterialTheme.typography.titleMedium)
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Haftalık fotoğraf",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp))
                )
            }
            Text(r.feedback, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TrainingRow(t: com.azim.app.data.Training) {
    val doneMin = if (t.status == "done") t.plannedMin else ((t.endedAt - t.startedAt) / 60_000).toInt().coerceAtLeast(0)
    val label = when (t.status) {
        "done" -> "Tamamlandı"
        "quit" -> "Yarıda bıraktı"
        "emergency" -> "Acil çıkış"
        else -> t.status
    }
    Column(Modifier.fillMaxWidth()) {
        Text(t.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${Dates.fmtTs(t.startedAt)} · $doneMin/${t.plannedMin} dk · $label",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
