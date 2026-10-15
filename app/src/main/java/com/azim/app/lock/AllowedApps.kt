package com.azim.app.lock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.azim.app.data.Prefs

/**
 * Kilit sırasında açık kalan uygulamalar.
 * core: acil durum için her zaman açık (telefon/arama ekranı, sistem arayüzü).
 * optional: yalnızca zaman kilidinde açık (mesajlar, kamera, alarm + kullanıcının seçtikleri).
 * Odak oturumunda yalnızca core açıktır.
 */
object AllowedApps {
    private val systemPackages = setOf(
        "com.android.systemui", "android", "com.android.phone", "com.android.incallui",
        "com.android.server.telecom", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.samsung.android.incallui", "com.android.dialer",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller"
    )

    private data class Cache(val at: Long, val core: Set<String>, val optional: Set<String>)

    @Volatile
    private var cache: Cache? = null

    private fun resolve(ctx: Context, vararg intents: Intent): Set<String> {
        val pm = ctx.packageManager
        val out = mutableSetOf<String>()
        for (i in intents) {
            runCatching {
                for (ri in pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)) {
                    out.add(ri.activityInfo.packageName)
                }
            }
        }
        return out
    }

    private fun build(ctx: Context): Cache {
        val core = mutableSetOf<String>()
        core.addAll(systemPackages)
        core.addAll(resolve(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))))
        runCatching {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.defaultDialerPackage?.let { core.add(it) }
        }

        val optional = mutableSetOf<String>()
        optional.addAll(resolve(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))))
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx)?.let { optional.add(it) } }
        optional.addAll(resolve(ctx, Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
        optional.addAll(resolve(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        optional.addAll(Prefs.get(ctx).extraAllowed)
        return Cache(System.currentTimeMillis(), core, optional)
    }

    private fun get(ctx: Context): Cache {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.at < 15_000) return c
        return build(ctx).also { cache = it }
    }

    fun invalidate() { cache = null }

    fun core(ctx: Context): Set<String> = get(ctx).core
    fun optional(ctx: Context): Set<String> = get(ctx).optional

    fun isAllowed(ctx: Context, pkg: String, cls: String?, state: LockState): Boolean {
        if (pkg == ctx.packageName) {
            // Odak oturumunda yalnızca kilit ekranının kendisi serbest.
            return state == LockState.TIME || cls == LockActivity::class.java.name
        }
        if (pkg in core(ctx)) return true
        if (state == LockState.TIME && pkg in optional(ctx)) return true
        return false
    }
}
