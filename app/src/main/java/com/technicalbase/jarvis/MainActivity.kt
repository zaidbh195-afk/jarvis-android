package com.technicalbase.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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

    private lateinit var mainContainer: LinearLayout
    private val http = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var currentProjectId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        setContentView(mainContainer)
        
        showHomeScreen()
    }

    private fun showHomeScreen() {
        mainContainer.removeAllViews()
        val title = TextView(this).apply { text = "Jarvis AI Builder"; textSize = 24f; setTypeface(null, Typeface.BOLD) }
        mainContainer.addView(title)

        mainContainer.addView(Button(this).apply {
            text = "➕ Start New Project"
            setOnClickListener { showNewProjectScreen() }
        })

        mainContainer.addView(Button(this).apply {
            text = "📁 Project History"
            setOnClickListener { showHistoryScreen() }
        })

        mainContainer.addView(Button(this).apply {
            text = "⚙️ Settings & Permissions"
            setOnClickListener { showSettingsScreen() }
        })

        val recentStatusText = TextView(this).apply { 
            text = "\nRecent Process:\nLoading status..."; 
            textSize = 14f; 
            setPadding(0, 40, 0, 0)
            setTextColor(Color.GRAY)
        }
        mainContainer.addView(recentStatusText)

        val req = Request.Builder().url("http://127.0.0.1:8765/api/projects").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val o = JSONObject(body ?: "{}")
                        val keys = o.keys()
                        var activeId = ""
                        var activeStep = ""
                        var activeProgress = 0
                        
                        while (keys.hasNext()) {
                            val id = keys.next()
                            val proj = o.getJSONObject(id)
                            if (proj.optBoolean("active", false)) {
                                activeId = id
                                activeStep = proj.optString("step_name", "")
                                activeProgress = proj.optInt("progress", 0)
                            }
                        }
                        
                        if (activeId.isNotEmpty()) {
                            recentStatusText.text = "\n🟢 Active Process: $activeId\nStatus: $activeStep ($activeProgress%)"
                            recentStatusText.setTextColor(Color.parseColor("#4CAF50"))
                            recentStatusText.setOnClickListener { showStatusScreen(activeId) }
                        } else {
                            recentStatusText.text = "\nNo active processes running."
                            recentStatusText.setTextColor(Color.GRAY)
                        }
                    } catch (_: Exception) {}
                }
            }
        })
    }

    private fun showNewProjectScreen() {
        mainContainer.removeAllViews()
        mainContainer.addView(TextView(this).apply { text = "Problem Statement"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        
        val input = EditText(this).apply { hint = "Describe what you want to build..."; setMinLines(5) }
        mainContainer.addView(input)

        mainContainer.addView(Button(this).apply {
            text = "Send to Jarvis"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) sendPromptToBrain(text)
            }
        })

        mainContainer.addView(Button(this).apply {
            text = "⬅ Back"
            setOnClickListener { showHomeScreen() }
        })
    }

    private fun showHistoryScreen() {
        mainContainer.removeAllViews()
        
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        val title = TextView(this).apply { 
            text = "Project History"; 
            textSize = 20f; 
            setTypeface(null, Typeface.BOLD) 
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(title)
        
        headerLayout.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                val req = Request.Builder()
                    .url("http://127.0.0.1:8765/api/projects/clear")
                    .post(RequestBody.create("application/json".toMediaType(), "{}"))
                    .build()
                    
                http.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        runOnUiThread { showHistoryScreen() }
                    }
                })
            }
        })
        mainContainer.addView(headerLayout)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        mainContainer.addView(scroll)

        val req = Request.Builder().url("http://127.0.0.1:8765/api/projects").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { list.addView(TextView(this@MainActivity).apply { text = "Error: Is brain running?" }) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val o = JSONObject(body ?: "{}")
                        val keys = o.keys()
                        if (!keys.hasNext()) list.addView(TextView(this@MainActivity).apply { text = "No projects yet." })
                        
                        while (keys.hasNext()) {
                            val id = keys.next()
                            val proj = o.getJSONObject(id)
                            val step = proj.optString("step_name", "Unknown")
                            val progress = proj.optInt("progress", 0)
                            val isActive = proj.optBoolean("active", false)
                            val color = if(isActive) "#4CAF50" else "#888888"
                            
                            val btn = Button(this@MainActivity).apply {
                                text = "$id\nStatus: $step ($progress%)"
                                setTextColor(Color.parseColor(color))
                                setOnClickListener { showStatusScreen(id) }
                            }
                            list.addView(btn)
                        }
                    } catch (_: Exception) {}
                }
            }
        })

        mainContainer.addView(Button(this).apply { text = "⬅ Back"; setOnClickListener { showHomeScreen() } })
    }

    private fun showStatusScreen(projectId: String) {
        currentProjectId = projectId
        mainContainer.removeAllViews()
        
        mainContainer.addView(TextView(this).apply { text = "Project: $projectId"; textSize = 20f; setTypeface(null, Typeface.BOLD) })

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.VISIBLE; progress = 0
        }
        mainContainer.addView(progressBar)

        val progressText = TextView(this).apply { textSize = 14f; setPadding(0, 16, 0, 16); text = "Loading..." }
        mainContainer.addView(progressText)
        
        val viewSiteBtn = Button(this).apply {
            text = "🌐 View Project on GitHub"
            visibility = View.GONE
            setOnClickListener {
                val url = it.tag.toString()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        mainContainer.addView(viewSiteBtn)

        val retryBtn = Button(this).apply {
            text = "🔄 Retry Build"
            visibility = View.GONE
            setOnClickListener {
                val req = Request.Builder()
                    .url("http://127.0.0.1:8765/api/projects/retry/$projectId")
                    .post(RequestBody.create(null, ""))
                    .build()
                http.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Retry started!", Toast.LENGTH_SHORT).show()
                            retryBtn.visibility = View.GONE
                            isPolling = true
                            pollProjectStatus(projectId, progressBar, progressText, logText, viewSiteBtn, retryBtn)
                        }
                    }
                })
            }
        }
        mainContainer.addView(retryBtn)

        val logText = TextView(this).apply { textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 16, 0, 16) }
        mainContainer.addView(logText)

        mainContainer.addView(Button(this).apply { text = "⬅ Back to Home"; setOnClickListener { isPolling = false; showHomeScreen() } })

        isPolling = true
        pollProjectStatus(projectId, progressBar, progressText, logText, viewSiteBtn, retryBtn)
    }

    private fun pollProjectStatus(id: String, pb: ProgressBar, pt: TextView, lt: TextView, viewBtn: Button, retryBtn: Button) {
        if (!isPolling || currentProjectId != id) return
        val req = Request.Builder().url("http://127.0.0.1:8765/api/projects/$id").build()
        
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
                        
                        runOnUiThread {
                            pb.progress = progress
                            pt.text = "Progress: $progress%\nStatus: $step"
                            
                            if (!active && progress < 100) {
                                retryBtn.visibility = View.VISIBLE
                            } else {
                                retryBtn.visibility = View.GONE
                            }
                            
                            val logsArray = o.optJSONArray("logs")
                            if (logsArray != null) {
                                val sb = StringBuilder()
                                for (i in 0 until logsArray.length()) { 
                                    val logStr = logsArray.getString(i)
                                    sb.append(logStr).append("\n")
                                    if (logStr.contains("Project URL:")) {
                                        val url = logStr.substringAfter("Project URL:").trim()
                                        viewBtn.visibility = View.VISIBLE
                                        viewBtn.tag = url
                                    }
                                }
                                lt.text = sb.toString()
                            }
                            
                            if (!active && progress == 100) {
                                pt.text = "Build Complete!"
                                isPolling = false
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        })
        if (isPolling) handler.postDelayed({ pollProjectStatus(id, pb, pt, lt, viewBtn, retryBtn) }, 2000)
    }

    private fun showSettingsScreen() {
        mainContainer.removeAllViews()
        mainContainer.addView(TextView(this).apply { text = "Settings"; textSize = 20f; setTypeface(null, Typeface.BOLD) })

        mainContainer.addView(Button(this).apply {
            text = "1. Start Background Service"
            setOnClickListener { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, ForegroundService::class.java)); Toast.makeText(this@MainActivity, "Started", Toast.LENGTH_SHORT).show() }
        })
        mainContainer.addView(Button(this).apply {
            text = "2. Enable Accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        mainContainer.addView(Button(this).apply {
            text = "3. Grant VPN permission"
            setOnClickListener {
                val prep = AndroidVpnService.prepare(this@MainActivity)
                if (prep != null) startActivityForResult(prep, 100)
            }
        })
        mainContainer.addView(Button(this).apply {
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
        mainContainer.addView(Button(this).apply { text = "⬅ Back"; setOnClickListener { showHomeScreen() } })
    }

    private fun sendPromptToBrain(text: String) {
        val json = JSONObject().apply { put("text", "build a custom project: $text"); put("source", "android_app") }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val req = Request.Builder().url("http://127.0.0.1:8765/intent").header("X-Jarvis-Token", "change-me-on-first-boot").post(body).build()

        Toast.makeText(this, "Sending...", Toast.LENGTH_SHORT).show()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: Brain offline.", Toast.LENGTH_LONG).show() }
            }
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Build started!", Toast.LENGTH_LONG).show()
                    showHomeScreen()
                }
            }
        })
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
    }
}
