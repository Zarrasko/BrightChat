package com.gios.lightchat.socket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gios.lightchat.api.Store

/**
 * Restarts the live-socket [SocketService] after a reboot so messages keep
 * arriving (and notifying) without the user opening the app — the difference
 * between a demo and an always-on texting app. Only fires up once setup has run
 * (a server URL and password are stored); BOOT_COMPLETED arrives post-unlock, so
 * encrypted prefs / Keystore are available. The service's `remoteMessaging` type
 * is allowed to start from boot on Android 14+ (dataSync would be blocked).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Store.hasPassword(context) || Store.baseUrl(context) == null) return
        context.startForegroundService(Intent(context, SocketService::class.java))
    }
}
