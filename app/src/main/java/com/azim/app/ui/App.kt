package com.azim.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.azim.app.AppViewModel

/** Ekran her öne geldiğinde artan sayaç; izin durumu gibi sistem bilgilerini yenilemek için. */
@Composable
fun rememberResumeTick(): State<Int> {
    val tick = remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick.intValue += 1 }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun AzimApp(vm: AppViewModel) {
    val ready by vm.ready.collectAsState()
    val user by vm.user.collectAsState()
    val goals by vm.goals.collectAsState()
    val editingProfile by vm.editingProfile.collectAsState()
    val profileVersion by vm.profileVersion.collectAsState()
    val needProfile = remember(user, profileVersion) {
        user?.let { !vm.prefs.profileDone(it.id) } ?: false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            user == null -> AuthScreen(vm)
            goals == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            goals!!.isEmpty() -> GoalScreen(vm, first = true, onDone = {}, onBack = {})
            needProfile || editingProfile -> ProfileScreen(vm)
            else -> MainShell(vm)
        }
    }
}

@Composable
private fun MainShell(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addingGoal by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Koç bildirimine dokunulunca sohbet sekmesine geç.
    val openChat by vm.openChat.collectAsState()
    LaunchedEffect(openChat) {
        if (openChat) { tab = 1; vm.openChat.value = false }
    }

    if (addingGoal) {
        BackHandler { addingGoal = false }
        GoalScreen(vm, first = false, onDone = { addingGoal = false }, onBack = { addingGoal = false })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val items = listOf(
                    "Bugün" to Icons.Filled.Home,
                    "Koç" to Icons.Filled.Person,
                    "İlerleme" to Icons.Filled.Star,
                    "Ayarlar" to Icons.Filled.Settings
                )
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(vm, onAddGoal = { addingGoal = true }, onOpenSettings = { tab = 3 }, onOpenProgress = { tab = 2 })
                1 -> ChatScreen(vm)
                2 -> ProgressScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
