package com.technicalbase.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.technicalbase.jarvis.service.ForegroundService

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        status = TextView(this).apply { textSize = 14f }
        layout.addView(status)

        layout.addView(Button(this).apply {
            text = "1. Start Jarvis"
            setOnClickListener {
                requestNotifications()
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, ForegroundService::class.java)
                )
                refresh()
            }
        })

        layout.addView(Button(this).apply {
            text = "2. Enable Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = "3. Grant VPN permission"
            setOnClickListener {
                val prep = AndroidVpnService.prepare(this@MainActivity)
                if (prep != null) startActivityForResult(prep, 100)
                else refresh()
            }
        })

        layout.addView(Button(this).apply {
            text = "4. Disable battery optimization"
            setOnClickListener {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    refresh()
                } else {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    try { startActivity(i) }
                    catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        })

        layout.addView(Button(this).apply {
            text = "Refresh status"
            setOnClickListener { refresh() }
        })

        setContentView(layout)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) refresh()
    }

    private fun refresh() {
        val svcOn = com.technicalbase.jarvis.service.AccessibilityService.instance != null
        val vpnReady = AndroidVpnService.prepare(this) == null
        val vpnRunning = com.technicalbase.jarvis.service.VpnService.instance != null
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOK = pm.isIgnoringBatteryOptimizations(packageName)
        val server = com.technicalbase.jarvis.bridge.LocalServer.isRunning()

        status.text = buildString {
            appendLine("Jarvis v0.1.0")
            appendLine("Server        : ${if (server) "8766 (up)" else "stopped"}")
            appendLine("Accessibility : ${if (svcOn) "ON" else "off"}")
            appendLine("VPN ready     : ${if (vpnReady) "yes" else "no — tap 3"}")
            appendLine("VPN active    : ${if (vpnRunning) "yes" else "no"}")
            appendLine("Battery opt   : ${if (batteryOK) "exempt" else "restricted — tap 4"}")
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }
}
