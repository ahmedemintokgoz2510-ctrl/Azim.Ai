package com.azim.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azim.app.AppViewModel
import com.azim.app.data.Profile

/** Koçun seni tanıması için başlangıç değerlendirmesi. İlk girişte bir kez, sonra Ayarlar'dan düzenlenir. */
@Composable
fun ProfileScreen(vm: AppViewModel) {
    val uid = vm.user.value?.id ?: 0L
    val saved = remember { vm.prefs.profile(uid) }
    var state by remember { mutableStateOf(saved.state) }
    var vision by remember { mutableStateOf(saved.vision) }
    var obstacles by remember { mutableStateOf(saved.obstacles) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Seni tanıyalım", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Koçun sana göre konuşabilmesi için dürüst yaz. Hazır bir düzenin yoksa sorun değil; sadece ne olmak istediğini yaz, planı koç çıkarır.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = state, onValueChange = { state = it },
            label = { Text("Şu anki durumun (fiziksel, zihinsel, dersler…)") },
            modifier = Modifier.fillMaxWidth(), minLines = 3
        )
        OutlinedTextField(
            value = vision, onValueChange = { vision = it },
            label = { Text("Ne olmak / nereye gelmek istiyorsun?") },
            modifier = Modifier.fillMaxWidth(), minLines = 3
        )
        OutlinedTextField(
            value = obstacles, onValueChange = { obstacles = it },
            label = { Text("Seni en çok ne engelliyor?") },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )

        Text(
            "Bu bilgiler, koçun yanıtları için Gemini'ye gönderilen komutlara eklenir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = { vm.saveProfile(Profile(state, vision, obstacles)) },
            enabled = vision.trim().length >= 3,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Kaydet") }

        TextButton(onClick = { vm.skipProfile() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.prefs.profileDone(uid)) "Vazgeç" else "Şimdilik atla")
        }
    }
}
