package com.technicalbase.jarvis.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService as BaseVpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class VpnService : BaseVpnService() {

    companion object {
        private const val TAG = "Jarvis.Vpn"
        private const val CHANNEL = "jarvis_vpn"
        private const val NOTIF_ID = 2002
        private const val STATE_FILE = "lockdown.json"

        @Volatile var instance: VpnService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context, durationMin: Int, kind: String, reason: String) {
            val i = Intent(ctx, VpnService::class.java)
            i.action = "START"
            i.putExtra("duration_min", durationMin)
            i.putExtra("kind", kind)
            i.putExtra("reason", reason)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, VpnService::class.java)
            i.action = "STOP"
            ctx.startService(i)
        }
    }

    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var blocked: Boolean = false
    @Volatile private var tunnelReady: Boolean = false
    private var deadlineMs: Long = 0L
    private var kind: String = "none"
    private var reasonText: String = ""
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onDestroy() {
        instance = null
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        tunnelReady = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground() immediately — otherwise Android kills us
        startForeground(NOTIF_ID, buildNotification("Jarvis VPN"))

        val action = intent?.action ?: "RESTORE"

        if (action == "RESTORE" || deadlineMs == 0L) loadState()

        when (action) {
            "START" -> {
                val dur = intent?.getIntExtra("duration_min", 30) ?: 30
                val k = intent?.getStringExtra("kind") ?: "manual"
                val r = intent?.getStringExtra("reason") ?: ""
                startLockdown(dur, k, r)
            }
            "STOP" -> {
                if (kind == "keyword") {
                    Log.w(TAG, "STOP refused — keyword lockdown")
                } else {
                    stopLockdown()
                }
            }
            "RESTORE" -> {
                if (deadlineMs > 0 && System.currentTimeMillis() < deadlineMs) {
                    blocked = true
                    establishTunnel()
                    scheduleExpiry()
                } else if (deadlineMs > 0) {
                    stopLockdown()
                }
            }
        }
        return START_STICKY
    }

    private fun startLockdown(durationMin: Int, k: String, r: String) {
        val now = System.currentTimeMillis()
        val newDeadline = now + durationMin * 60_000L
        deadlineMs = maxOf(deadlineMs, newDeadline)
        kind = k
        reasonText = r
        blocked = true
        saveState()
        establishTunnel()
        scheduleExpiry()
        Log.i(TAG, "lockdown started until $deadlineMs kind=$k tunnel=$tunnelReady")
    }

    private fun stopLockdown() {
        blocked = false
        tunnelReady = false
        deadlineMs = 0
        kind = "none"
        reasonText = ""
        saveState()
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "lockdown ended")
    }

    private fun establishTunnel() {
        if (tunnel != null && tunnelReady) return

        val builder = Builder()
            .setSession("Jarvis")
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1::1", 128)
            .addRoute("::", 0)
            .setBlocking(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Explicitly disallow our own app + Termux so we can still control it
            try {
                builder.addDisallowedApplication(packageName)
                builder.addDisallowedApplication("com.termux")
            } catch (_: Exception) {}
        }

        tunnel = builder.establish()
        if (tunnel == null) {
            Log.e(TAG, "establish() returned NULL — VPN permission missing")
            tunnelReady = false
            return
        }
        tunnelReady = true

        val fd = tunnel!!
        worker.execute {
            val inStream = ParcelFileDescriptor.AutoCloseInputStream(fd)
            val buf = ByteArray(32767)
            try {
                while (blocked && !Thread.currentThread().isInterrupted) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    // Discard
                }
            } catch (e: Exception) {
                Log.w(TAG, "drain ended: ${e.message}")
            } finally {
                try { inStream.close() } catch (_: Exception) {}
            }
        }
    }

    private fun scheduleExpiry() {
        val remaining = deadlineMs - System.currentTimeMillis()
        if (remaining <= 0) { stopLockdown(); return }
        worker.execute {
            try { Thread.sleep(remaining) } catch (_: InterruptedException) {}
            if (System.currentTimeMillis() >= deadlineMs) stopLockdown()
        }
    }

    private fun stateFile(): File = File(filesDir, STATE_FILE)

    private fun saveState() {
        try {
            val o = JSONObject()
            o.put("blocked", blocked)
            o.put("deadline_ms", deadlineMs)
            o.put("kind", kind)
            o.put("reason", reasonText)
            stateFile().writeText(o.toString())
        } catch (e: Exception) { Log.w(TAG, "save: ${e.message}") }
    }

    private fun loadState() {
        try {
            val f = stateFile()
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            deadlineMs = o.optLong("deadline_ms", 0)
            kind = o.optString("kind", "none")
            reasonText = o.optString("reason", "")
        } catch (e: Exception) { Log.w(TAG, "load: ${e.message}") }
    }

    fun getState(): JSONObject {
        val o = JSONObject()
        val now = System.currentTimeMillis()
        val remaining = maxOf(0L, deadlineMs - now)
        val active = blocked && tunnelReady && remaining > 0
        o.put("active", active)
        o.put("kind", kind)
        o.put("deadline_ms", deadlineMs)
        o.put("remaining_ms", remaining)
        o.put("reason", reasonText)
        o.put("tunnel", tunnelReady)
        return o
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        tunnelReady = false
        if (kind == "keyword" && System.currentTimeMillis() < deadlineMs) {
            blocked = true
            try { Thread.sleep(500) } catch (_: Exception) {}
            establishTunnel()
        } else {
            stopLockdown()
        }
        super.onRevoke()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Jarvis Lockdown")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_busy)
            .setOngoing(true)
            .build()
    }
}
