package com.technicalbase.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        status = TextView(this).apply {
            textSize = 14f
        }
        layout.addView(status)

        layout.addView(Button(this).apply {
            text = "1. Start Jarvis"
            setOnClickListener {
                requestNotifications()
                val i = Intent(this@MainActivity, ForegroundService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, i)
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
            text = "Refresh status"
            setOnClickListener { refresh() }
        })

        setContentView(layout)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val svcOn = com.technicalbase.jarvis.service.AccessibilityService.instance != null
        status.text = buildString {
            appendLine("Jarvis v0.1.0")
            appendLine("Server   : 127.0.0.1:8766")
            appendLine("Accessibility : ${if (svcOn) "ON" else "off"}")
            appendLine()
            append("If accessibility is off, tap button 2 and enable ")
            append("\"Jarvis\" in the list.")
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
