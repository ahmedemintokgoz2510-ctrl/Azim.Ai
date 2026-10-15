package com.azim.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azim.app.ai.TrainingTarget
import com.azim.app.data.DailyTask
import com.azim.app.data.Goal
import kotlin.math.roundToInt

private data class TrainingOpt(val key: String, val label: String, val target: TrainingTarget, val minutes: Int)

/** Eğitime geçerken konu, süre ve niyet seçilir. Başlayınca ek uygulamalar kapanır, yalnızca telefon açık kalır. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrainingDialog(
    tasks: List<DailyTask>,
    goals: List<Goal>,
    preselectTaskId: Long,
    onDismiss: () -> Unit,
    onStart: (TrainingTarget, Int, String) -> Unit
) {
    val options = remember(tasks, goals) {
        tasks.filter { !it.done }.map {
            TrainingOpt("t${it.id}", it.title, TrainingTarget(it.id, it.goalId, it.title, it.detail), it.minutes)
        } + goals.map {
            TrainingOpt("g${it.id}", "Serbest: ${it.title}", TrainingTarget(0L, it.id, "Serbest eğitim · ${it.title}", ""), 45)
        }
    }

    fun snap(m: Int) = ((m / 5.0).roundToInt() * 5).coerceIn(10, 180)

    var selectedKey by remember {
        mutableStateOf(options.firstOrNull { it.key == "t$preselectTaskId" }?.key ?: options.firstOrNull()?.key ?: "")
    }
    var minutes by remember { mutableIntStateOf(snap(options.firstOrNull { it.key == selectedKey }?.minutes ?: 45)) }
    var intent by remember { mutableStateOf("") }
    val selected = options.firstOrNull { it.key == selectedKey }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eğitime geç") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Konu", style = MaterialTheme.typography.titleMedium)
                for (o in options) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selectedKey = o.key; if (o.target.taskId > 0) minutes = snap(o.minutes) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedKey == o.key,
                            onClick = { selectedKey = o.key; if (o.target.taskId > 0) minutes = snap(o.minutes) }
                        )
                        Text(o.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text("Süre: $minutes dakika", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(25, 45, 60, 90)) {
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m dk") })
                    }
                }
                Slider(
                    value = minutes.toFloat(),
                    onValueChange = { minutes = snap(it.roundToInt()) },
                    valueRange = 10f..180f,
                    steps = 33
                )

                OutlinedTextField(
                    value = intent,
                    onValueChange = { intent = it },
                    label = { Text("Bu sürede tam olarak ne yapacaksın? (isteğe bağlı)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Text(
                    "Başlayınca mesajlar ve ek uygulamalar kapanır; yalnızca telefon açık kalır. Bırakmak istersen koç önce sana bir şey söyler, kararı yine sen verirsin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onStart(it.target, minutes, intent) } },
                enabled = selected != null
            ) { Text("Başla") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
