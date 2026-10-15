package com.azim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azim.app.AppViewModel
import com.azim.app.ai.Categories

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GoalScreen(vm: AppViewModel, first: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Categories.all.first()) }
    var level by remember { mutableStateOf(Categories.levels.first()) }
    var minutes by remember { mutableFloatStateOf(45f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(if (first) "Hedefini belirle" else "Yeni hedef", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Koçun her gün bu hedefe göre görev üretecek. Net bir hedefin yoksa ne olmak istediğini yaz; koç oradan plan çıkarır.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Hedef (ör. TYT Fizik'te 30 net, sigarayı bırakmak)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )

        Text("Alan", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in Categories.all) {
                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
            }
        }

        Text("Seviye", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (l in Categories.levels) {
                FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l) })
            }
        }

        Text("Günlük süre: ${minutes.toInt()} dakika", style = MaterialTheme.typography.titleMedium)
        Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 15f..180f, steps = 10)

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.addGoal(title, category, level, minutes.toInt(), onDone) },
            enabled = title.trim().length >= 3,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (first) "Başlayalım" else "Hedefi ekle") }

        if (!first) {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Vazgeç") }
        }
    }
}
