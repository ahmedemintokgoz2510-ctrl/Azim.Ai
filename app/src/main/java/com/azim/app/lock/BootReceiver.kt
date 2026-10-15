package com.azim.app.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.azim.app.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.get(context).userId > 0) LockService.start(context)
    }
}
