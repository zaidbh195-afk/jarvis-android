package com.technicalbase.jarvis.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.technicalbase.jarvis.service.ForegroundService
import com.technicalbase.jarvis.service.VpnService
import org.json.JSONObject
import java.io.File

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return

        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED") {
            return
        }

        Log.i("Jarvis.Boot", "Boot detected — starting Jarvis")

        // 1. Start ForegroundService (starts LocalServer on 8766)
        try {
            val svc = Intent(ctx, ForegroundService::class.java)
            svc.action = "BOOT_START"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc)
            } else {
                ctx.startService(svc)
            }
        } catch (e: Exception) {
            Log.e("Jarvis.Boot", "Failed to start service: ${e.message}")
        }

        // 2. Restore VpnService if a lockdown was active before reboot
        try {
            val stateFile = File(ctx.filesDir, "lockdown.json")
            if (stateFile.exists()) {
                val o = JSONObject(stateFile.readText())
                val deadlineMs = o.optLong("deadline_ms", 0)
                val kind = o.optString("kind", "manual")
                
                // If the timer hasn't expired yet, force the VPN back on
                if (deadlineMs > System.currentTimeMillis()) {
                    Log.i("Jarvis.Boot", "Active lockdown found! Restoring VPN...")
                    VpnService.start(ctx, 30, kind, "Reboot Recovery")
                }
            }
        } catch (e: Exception) {
            Log.w("Jarvis.Boot", "VPN restore skipped: ${e.message}")
        }
    }
}
