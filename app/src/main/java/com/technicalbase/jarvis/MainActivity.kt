package com.technicalbase.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService as AndroidVpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
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
    
    // Progress UI
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logText: TextView

    private val http = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        status = TextView(this).apply { textSize = 14f }
        layout.addView(status)

        promptInput = EditText(this).apply { hint = "Type your problem statement..."; setMinLines(3) }
        layout.addView(promptInput)

        layout.addView(Button(this).apply {
            text = "Send to Jarvis Brain"
            setOnClickListener { sendPromptToBrain() }
        })

        // --- NEW PROGRESS UI ---
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 100
    progress = 0
    visibility = View.VISIBLE // Change to VISIBLE
}

progressText = TextView(this).apply { 
    text = "Ready"
    textSize = 12f
    visibility = View.VISIBLE // Change to VISIBLE
    setPadding(0, 16, 0, 16) 
}

logText = TextView(this).apply { 
    text = "Logs will appear here..."
    textSize = 10f
    visibility = View.VISIBLE // Change to VISIBLE
    setTextColor(0xFF888888.toInt()) 
}

        // ------------------------

        layout.addView(Button(this).apply {
            text = "1. Start Jarvis"
            setOnClickListener {
                ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, ForegroundService::class.java))
                refresh()
            }
        })

        layout.addView(Button(this).apply {
            text = "2. Enable Accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        layout.addView(Button(this).apply {
            text = "3. Grant VPN permission"
            setOnClickListener {
                val prep = AndroidVpnService.prepare(this@MainActivity)
                if (prep != null) startActivityForResult(prep, 100) else refresh()
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

        setContentView(layout)
        refresh()
    }

    private fun sendPromptToBrain() {
        val text = promptInput.text.toString().trim()
        if (text.isEmpty()) return

        val json = JSONObject().apply { put("text", "build a custom project: $text"); put("source", "android_app") }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val req = Request.Builder().url("http://127.0.0.1:8765/intent").header("X-Jarvis-Token", "change-me-on-first-boot").post(body).build()

        Toast.makeText(this, "Sending to Brain...", Toast.LENGTH_SHORT).show()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: Brain offline.", Toast.LENGTH_LONG).show() }
            }
            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string()
                runOnUiThread {
                    try {
                        val o = JSONObject(respBody ?: "{}")
                        Toast.makeText(this@MainActivity, o.optString("text", "Sent!"), Toast.LENGTH_LONG).show()
                        promptInput.setText("")
                        if (o.optString("intent") == "build_custom_project") {
                            startPollingProgress()
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    // --- NEW POLLING LOGIC ---
    private fun startPollingProgress() {
        if (isPolling) return
        isPolling = true
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        logText.visibility = View.VISIBLE
        pollBuildStatus()
    }

    private fun pollBuildStatus() {
        if (!isPolling) return
        val req = Request.Builder().url("http://127.0.0.1:8765/build_status").build()
        
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (body != null) {
                    try {
                        val o = JSONObject(body)
                        val active = o.optBoolean("active", false)
                        val progress = o.optInt("progress", 0)
                        val step = o.optString("step_name", "")
                        val eta = o.optInt("eta_seconds", 0)
                        
                        runOnUiThread {
                            progressBar.progress = progress
                            progressText.text = "Progress: $progress%\nStep: $step\nETA: ${eta / 60} min"
                            
                            val logsArray = o.optJSONArray("logs")
                            if (logsArray != null) {
                                val sb = StringBuilder()
                                for (i in 0 until logsArray.length()) { sb.append(logsArray.getString(i)).append("\n") }
                                logText.text = sb.toString()
                            }
                            
                            if (!active && progress == 100) {
                                progressText.text = "Build Complete!\nCheck GitHub."
                                isPolling = false
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        })
        
        if (isPolling) handler.postDelayed({ pollBuildStatus() }, 2000) // Poll every 2 seconds
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) refresh()
    }

    private fun refresh() {
        val svcOn = com.technicalbase.jarvis.service.AccessibilityService.instance != null
        val server = com.technicalbase.jarvis.bridge.LocalServer.isRunning()
        status.text = "Jarvis v0.1.0\nBridge: ${if (server) "UP" else "DOWN"}\nAccessibility: ${if (svcOn) "ON" else "OFF"}"
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
    }
}
