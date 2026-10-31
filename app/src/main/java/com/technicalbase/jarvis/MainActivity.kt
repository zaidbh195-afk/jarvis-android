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
import android.text.method.ScrollingMovementMethod
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

    // ==========================================
    // SCREEN 1: HOME
    // ==========================================
    private fun showHomeScreen() {
        mainContainer.removeAllViews()
        val title = TextView(this).apply { text = "Jarvis AI"; textSize = 24f; setTypeface(null, Typeface.BOLD) }
        mainContainer.addView(title)

        mainContainer.addView(Button(this).apply {
            text = "💬 Universal AI Chat"
            setOnClickListener { showChatScreen() }
        })

        mainContainer.addView(Button(this).apply {
            text = "🧠 Train Jarvis (Teach Rules)"
            setOnClickListener { showTrainingScreen() }
        })

        mainContainer.addView(Button(this).apply {
            text = "➕ Start New Website Project"
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

    // ==========================================
    // SCREEN 2: UNIVERSAL CHAT
    // ==========================================
    private fun showChatScreen() {
        mainContainer.removeAllViews()
        mainContainer.addView(TextView(this).apply { text = "Universal AI Chat"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        
        val scroll = ScrollView(this)
        val chatHistory = TextView(this).apply { 
            text = "Jarvis: How can I help you today?\n\n"
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }
        scroll.addView(chatHistory)
        mainContainer.addView(scroll)

        val input = EditText(this).apply { hint = "Ask anything, write an article, or draw a picture..."; setMinLines(2) }
        mainContainer.addView(input)

        mainContainer.addView(Button(this).apply {
            text = "Send to Jarvis"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    chatHistory.append("You: $text\n\n")
                    input.setText("")
                    
                    sendToBrain(text, "chat_ui", onSuccess = { reply ->
                        chatHistory.append("Jarvis: $reply\n\n")
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }, onFailure = {
                        chatHistory.append("System: Brain offline.\n\n")
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    })
                }
            }
        })

        mainContainer.addView(Button(this).apply { text = "⬅ Back to Home"; setOnClickListener { showHomeScreen() } })
    }

    // ==========================================
    // SCREEN 3: TRAINING JARVIS
    // ==========================================
    private fun showTrainingScreen() {
        mainContainer.removeAllViews()
        mainContainer.addView(TextView(this).apply { text = "Train Jarvis"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        mainContainer.addView(TextView(this).apply { text = "Teach Jarvis how to think, what to include/exclude, or how to write. Example: 'Always write articles with a strict word limit.'"; setPadding(0, 16, 0, 16) })
        
        val input = EditText(this).apply { hint = "Type a new rule..."; setMinLines(3) }
        mainContainer.addView(input)

        val statusText = TextView(this).apply { textSize = 14f; setTextColor(Color.GREEN); setPadding(0, 16, 0, 16) }
        mainContainer.addView(statusText)

        mainContainer.addView(Button(this).apply {
            text = "Teach Rule"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val finalText = if (text.lowercase().startsWith("from now on") || text.lowercase().startsWith("teach") || text.lowercase().startsWith("remember")) text else "from now on $text"
                    statusText.text = "Saving..."
                    
                    sendToBrain(finalText, "training_ui", onSuccess = { reply ->
                        statusText.text = reply
                        input.setText("")
                    }, onFailure = {
                        statusText.setTextColor(Color.RED)
                        statusText.text = "Error: Brain offline."
                    })
                }
            }
        })

        mainContainer.addView(Button(this).apply { text = "⬅ Back"; setOnClickListener { showHomeScreen() } })
    }

    // ==========================================
    // SCREEN 4: NEW WEBSITE PROJECT
    // ==========================================
    private fun showNewProjectScreen() {
        mainContainer.removeAllViews()
        mainContainer.addView(TextView(this).apply { text = "Website Builder"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        
        val input = EditText(this).apply { hint = "Describe what you want to build..."; setMinLines(5) }
        mainContainer.addView(input)

        mainContainer.addView(Button(this).apply {
            text = "Build Website"
            setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    sendToBrain("build a custom project: $text", "android_app", onSuccess = { 
                        Toast.makeText(this@MainActivity, "Build started!", Toast.LENGTH_SHORT).show()
                        showHomeScreen() 
                    }, onFailure = {
                        Toast.makeText(this@MainActivity, "Brain offline.", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        })

        mainContainer.addView(Button(this).apply { text = "⬅ Back"; setOnClickListener { showHomeScreen() } })
    }

    // ==========================================
    // SCREEN 5: PROJECT HISTORY
    // ==========================================
    private fun showHistoryScreen() {
        mainContainer.removeAllViews()
        
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        val title = TextView(this).apply { 
            text = "Project History"; textSize = 20f; setTypeface(null, Typeface.BOLD) 
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(title)
        
        headerLayout.addView(Button(this).apply {
            text = "Clear"
            setOnClickListener {
                val req = Request.Builder().url("http://127.0.0.1:8765/api/projects/clear").post(RequestBody.create("application/json".toMediaType(), "{}")).build()
                http.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) { runOnUiThread { showHistoryScreen() } }
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
                            
                            list.addView(Button(this@MainActivity).apply {
                                text = "$id\nStatus: $step ($progress%)"
                                setTextColor(Color.parseColor(color))
                                setOnClickListener { showStatusScreen(id) }
                            })
                        }
                    } catch (_: Exception) {}
                }
            }
        })

        mainContainer.addView(Button(this).apply { text = "⬅ Back"; setOnClickListener { showHomeScreen() } })
    }

    // ==========================================
    // SCREEN 6: PROJECT STATUS
    // ==========================================
    private fun showStatusScreen(projectId: String) {
        currentProjectId = projectId
        mainContainer.removeAllViews()
        
        mainContainer.addView(TextView(this).apply { text = "Project: $projectId"; textSize = 20f; setTypeface(null, Typeface.BOLD) })

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.VISIBLE; progress = 0 }
        val progressText = TextView(this).apply { textSize = 14f; setPadding(0, 16, 0, 16); text = "Loading..." }
        
        val viewSiteBtn = Button(this).apply { text = "🌐 View Project on GitHub"; visibility = View.GONE }
        val retryBtn = Button(this).apply { text = "🔄 Retry Build"; visibility = View.GONE }
        val diagBtn = Button(this).apply { text = "🩺 View Diagnostics" }
        
        val suggestInput = EditText(this).apply { hint = "Add a new feature or fix"; setMinLines(2) }
        val suggestBtn = Button(this).apply { text = "✨ Apply Suggestion" }
        
        val logText = TextView(this).apply { textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 16, 0, 16); setMovementMethod(ScrollingMovementMethod()) }
        val backBtn = Button(this).apply { text = "⬅ Back to Home" }

        mainContainer.addView(progressBar)
        mainContainer.addView(progressText)
        mainContainer.addView(viewSiteBtn)
        mainContainer.addView(retryBtn)
        mainContainer.addView(diagBtn)
        mainContainer.addView(suggestInput)
        mainContainer.addView(suggestBtn)
        mainContainer.addView(logText)
        mainContainer.addView(backBtn)

        viewSiteBtn.setOnClickListener {
            val url = it.tag.toString()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        retryBtn.setOnClickListener {
            val req = Request.Builder().url("http://127.0.0.1:8765/api/projects/retry/$projectId").post(RequestBody.create(null, "")).build()
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
        
        suggestBtn.setOnClickListener {
            val suggestion = suggestInput.text.toString().trim()
            if (suggestion.isNotEmpty()) {
                val json = JSONObject().apply { put("suggestion", suggestion) }
                val body = RequestBody.create("application/json".toMediaType(), json.toString())
                val req = Request.Builder().url("http://127.0.0.1:8765/api/projects/improve/$projectId").post(body).build()
                http.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Improvement started!", Toast.LENGTH_SHORT).show()
                            suggestInput.setText("")
                            isPolling = true
                            pollProjectStatus(projectId, progressBar, progressText, logText, viewSiteBtn, retryBtn)
                        }
                    }
                })
            }
        }
        
        diagBtn.setOnClickListener { showDiagnosticsScreen(projectId) }
        backBtn.setOnClickListener { isPolling = false; showHomeScreen() }

        isPolling = true
        pollProjectStatus(projectId, progressBar, progressText, logText, viewSiteBtn, retryBtn)
    }

    // ==========================================
    // SCREEN 7: DIAGNOSTICS
    // ==========================================
    private fun showDiagnosticsScreen(projectId: String) {
        mainContainer.removeAllViews()
        mainContainer.addView(TextView(this).apply { text = "Diagnostics: $projectId"; textSize = 20f; setTypeface(null, Typeface.BOLD); setPadding(0,0,0,24) })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        mainContainer.addView(scroll)

        val req = Request.Builder().url("http://127.0.0.1:8765/api/projects/$projectId").build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                runOnUiThread {
                    try {
                        val o = JSONObject(body ?: "{}")
                        val diags = o.optJSONArray("diagnostics")
                        
                        if (diags == null || diags.length() == 0) {
                            list.addView(TextView(this@MainActivity).apply { text = "No issues detected so far." })
                        } else {
                            for (i in 0 until diags.length()) {
                                val d = diags.getJSONObject(i)
                                val issue = d.optString("issue", "Unknown Error")
                                val detail = d.optString("detail", "")
                                val fix = d.optString("fix", "")
                                val status = d.optString("status", "")
                                
                                val card = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
                                    setBackgroundColor(Color.parseColor("#222222"))
                                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,24) }
                                }
                                
                                var colorHex = "#FF9800"
                                if (status == "success" || status == "fixed") colorHex = "#4CAF50"
                                if (status == "failed") colorHex = "#F44336"
                                
                                card.addView(TextView(this@MainActivity).apply { text = "⚠️ $issue"; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(colorHex)); textSize = 16f })
                                card.addView(TextView(this@MainActivity).apply { text = "Details: $detail"; setPadding(0,8,0,8); textSize = 12f; setTextColor(Color.LTGRAY) })
                       
