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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.technicalbase.jarvis.service.ForegroundService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var promptInput: EditText
    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        status = TextView(this).apply { textSize = 14f }
        layout.addView(status)

        // --- NEW: Text Input for Problem Statement ---
        promptInput = EditText(this).apply {
            hint = "Type your problem statement here... e.g., 'Build an app that reads foreign textbooks...'"
            setMinLines(3)
        }
        layout.addView(promptInput)

        layout.addView(Button(this).apply {
            text = "Send to Jarvis Brain"
            setOnClickListener { sendPromptToBrain() }
        })
        // ---------------------------------------------

        layout.addView(Button(this).apply {
            text = "1. Start Jarvis"
            setOnClickListener {
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
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    try { startActivity(i) } catch (_: Exception) {}
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

    private fun sendPromptToBrain() {
        val text = promptInput.text.toString().trim()
        if (text.isEmpty()) return

        val json = JSONObject().apply {
            put("text", "build a custom project: $text")
            put("source", "android_app")
        }

        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val req = Request.Builder()
            .url("http://127.0.0.1:8765/intent")
            .header("X-Jarvis-Token", "change-me-on-first-boot")
            .post(body)
            .build()

        Toast.makeText(this, "Sending to Jarvis...", Toast.LENGTH_SHORT).show()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: Is the Ubuntu brain running?", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string()
                runOnUiThread {
                    try {
                        val o = JSONObject(respBody ?: "{}")
                        val reply = o.optString("text", "No reply")
                        promptInput.setText("") // clear input
                        Toast.makeText(this@MainActivity, reply, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Sent! Check Termux for progress.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) refresh()
    }

    private fun refresh() {
        val svcOn = com.technicalbase.jarvis.service.AccessibilityService.instance != null
        val server = com.technicalbase.jarvis.bridge.LocalServer.isRunning()
        status.text = buildString {
            appendLine("Jarvis v0.1.0")
            appendLine("Bridge (8766): ${if (server) "UP" else "DOWN"}")
            appendLine("Accessibility : ${if (svcOn) "ON" else "OFF"}")
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
    }
}
