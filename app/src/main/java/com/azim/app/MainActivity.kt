package com.azim.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.azim.app.data.Prefs
import com.azim.app.lock.LockActivity
import com.azim.app.lock.LockService
import com.azim.app.ui.AzimApp
import com.azim.app.ui.AzimTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AzimTheme { AzimApp(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // Koç bildirimine dokununca sohbet sekmesi açılır.
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("open_chat", false) == true) vm.openChat.value = true
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        val prefs = Prefs.get(this)
        if (prefs.userId > 0) {
            LockService.start(this)
            // Başlamış bir oturum varsa ana ekranda kalınamaz; kilit ekranına dön.
            if (prefs.sessionActive) {
                startActivity(Intent(this, LockActivity::class.java))
            }
        }
    }
}
