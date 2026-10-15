package com.azim.app.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.azim.app.AppViewModel
import com.azim.app.ai.Gemini
import com.azim.app.data.Dates
import com.azim.app.lock.AllowedApps
import com.azim.app.lock.LockPolicy
import com.azim.app.lock.LockState
import com.azim.app.lock.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class AppEntry(val label: String, val pkg: String)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    val user by vm.user.collectAsState()
    val tick by rememberResumeTick()

    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            locked = LockPolicy.state(ctx) != LockState.NONE
            delay(1000)
        }
    }

    val keyFields = remember { mutableStateListOf<String>().also { it.addAll(prefs.editableKeys()) } }
    var keySaved by remember { mutableStateOf(false) }
    var tone by remember { mutableIntStateOf(prefs.coachTone) }
    var remindersOn by remember { mutableStateOf(prefs.remindersOn) }
    val remTimes = remember { mutableStateListOf(prefs.remMin(0), prefs.remMin(1), prefs.remMin(2)) }

    var enabled by remember(tick) { mutableStateOf(prefs.shownEnabled()) }
    var startMin by remember(tick) { mutableIntStateOf(prefs.shownStart()) }
    var queuedNote by remember(tick) { mutableStateOf(prefs.hasPending()) }
    var emergency by remember(tick) { mutableIntStateOf(prefs.emergencyPerDay) }
    var confirmLogout by remember { mutableStateOf(false) }

    val extra = remember { mutableStateListOf<String>().also { it.addAll(prefs.extraAllowed) } }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun applyLock(newEnabled: Boolean, newStart: Int) {
        enabled = newEnabled
        startMin = newStart
        queuedNote = prefs.requestLockChange(newEnabled, newStart)
    }

    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Ayarlar", style = MaterialTheme.typography.headlineMedium) }

        // ---- Hesap ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Hesap", style = MaterialTheme.typography.titleLarge)
                Text("${user?.name ?: ""} · ${user?.email ?: ""}", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { confirmLogout = true }, enabled = !locked) { Text("Çıkış yap") }
                if (locked) {
                    Text(
                        "Kilit aktifken çıkış yapılamaz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { HorizontalDivider() }

        // ---- Yapay zeka ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yapay zeka koç", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Model: ${Gemini.MODEL}. Ücretsiz anahtarı aistudio.google.com/apikey adresinden alabilirsin. " +
                        "En fazla 4 anahtar gir: biri günlük ${Gemini.SOFT_CAP} isteğe yaklaşınca ya da kota hatası verince sıradakine geçilir. " +
                        "Kotalar proje başınadır; anahtarları farklı Google projelerinden almalısın.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (i in 0..3) {
                    OutlinedTextField(
                        value = keyFields[i],
                        onValueChange = { keyFields[i] = it; keySaved = false },
                        label = { Text("Anahtar ${i + 1}") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { prefs.setKeys(keyFields.toList()); keySaved = true }) { Text("Kaydet") }
                    if (keySaved) Text("  Kaydedildi", style = MaterialTheme.typography.bodySmall)
                }
                prefs.keys().forEachIndexed { i, k ->
                    Text(
                        "Anahtar ${i + 1}: bugün ${prefs.keyUseToday(k)} istek",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { HorizontalDivider() }

        // ---- Koç tonu ve profil ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Koç tonu", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Nazik", 2 to "Net", 3 to "Sert").forEach { (v, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = tone == v,
                            onClick = { tone = v; prefs.coachTone = v },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "Sert ton mazeretlerine itiraz eder ve seni kendinle yüzleştirir; hakaret etmez, aşağılamaz. Bağımlılık ve ruhsal hedeflerde nüks asla utandırma aracı yapılmaz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { vm.editingProfile.value = true }) { Text("Profilimi düzenle") }
            }
        }
        item { HorizontalDivider() }

        // ---- Günlük koç mesajları ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Günlük koç mesajları", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Günde 3 mesaj (sabah, öğle, akşam)")
                    Switch(checked = remindersOn, onCheckedChange = { remindersOn = it; prefs.remindersOn = it })
                }
                listOf("Sabah", "Öğle", "Akşam").forEachIndexed { slot, label ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$label: ${Dates.fmtTime(remTimes[slot])}")
                        OutlinedButton(
                            enabled = remindersOn,
                            onClick = {
                                TimePickerDialog(ctx, { _, h, m ->
                                    remTimes[slot] = h * 60 + m
                                    prefs.setRemMin(slot, h * 60 + m)
                                }, remTimes[slot] / 60, remTimes[slot] % 60, true).show()
                            }
                        ) { Text("Değiştir") }
                    }
                }
                Text(
                    "Mesaja dokununca koçla sohbet açılır. Mesajlar koç servisi çalışırken gelir; pil kısıtlamasını kaldırmayı unutma.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { HorizontalDivider() }

        // ---- Kilit ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Disiplin kilidi", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Kilit saati geldiğinde bugünün görevleri bitene kadar yalnızca telefon, mesajlar ve seçtiğin uygulamalar açılır. Oturum başlatınca süre bitene kadar sadece arama açık kalır.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Kilit açık")
                    Switch(checked = enabled, onCheckedChange = { applyLock(it, startMin) }, enabled = !locked)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Kilit saati: ${Dates.fmtTime(startMin)}")
                    OutlinedButton(
                        enabled = !locked,
                        onClick = {
                            TimePickerDialog(ctx, { _, h, m -> applyLock(enabled, h * 60 + m) }, startMin / 60, startMin % 60, true).show()
                        }
                    ) { Text("Değiştir") }
                }
                if (queuedNote && prefs.hasPending()) {
                    Text(
                        "Kilidi gevşeten değişiklikler 24 saat sonra uygulanır: ${Dates.fmtTs(prefs.pendingApplyAt())}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Kilidi güçlendiren değişiklikler hemen, gevşetenler (kapatma, saati geciktirme) 24 saat sonra uygulanır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("Günlük acil durum çıkışı (10 dk)", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in 0..2) {
                        androidx.compose.material3.FilterChip(
                            selected = emergency == n,
                            onClick = { if (!locked) { emergency = n; prefs.emergencyPerDay = n } },
                            label = { Text("$n hak") }
                        )
                    }
                }
            }
        }
        item { HorizontalDivider() }

        // ---- İzinler ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("İzinler", style = MaterialTheme.typography.titleLarge)
                PermRow("Kullanım erişimi", "Hangi uygulamanın açık olduğunu görmek için", remember(tick) { Perms.usageGranted(ctx) }) {
                    Perms.openUsageSettings(ctx)
                }
                PermRow("Üstte gösterme", "Kilit ekranını diğer uygulamaların üstüne getirmek için", remember(tick) { Perms.overlayGranted(ctx) }) {
                    Perms.openOverlaySettings(ctx)
                }
                PermRow("Bildirimler", "Arka plan servisi bildirimi için", remember(tick) { Perms.notificationsGranted(ctx) }) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                PermRow("Pil kısıtlaması yok", "Servisin sistem tarafından durdurulmaması için", remember(tick) { Perms.batteryUnrestricted(ctx) }) {
                    Perms.openBatterySettings(ctx)
                }
                Text(
                    "Not: Bu bir öz-disiplin aracıdır. Kilit; izinleri kapatarak, uygulamayı kaldırarak ya da güvenli modda açarak aşılabilir. Amaç seni zorlamak değil, kararını güçlendirmek.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { HorizontalDivider() }

        // ---- İzinli uygulamalar ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Kilitteyken açık kalacak uygulamalar", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Telefon her zaman açıktır. Mesajlar, kamera ve alarm kilit saatinde açık; aşağıdan ekleme yapabilirsin (odak oturumunda hiçbiri açılmaz).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(apps, key = { it.pkg }) { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = app.pkg in extra,
                    enabled = !locked,
                    onCheckedChange = { on ->
                        if (on) extra.add(app.pkg) else extra.remove(app.pkg)
                        prefs.extraAllowed = extra.toSet()
                        AllowedApps.invalidate()
                    }
                )
                Text(app.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Çıkış yapılsın mı?") },
            text = { Text("Çıkış yapınca kilit de devre dışı kalır. Verilerin telefonda saklı kalır.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Çıkış yap") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text("Tamam", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = onGrant) { Text("Ver") }
        }
    }
}
