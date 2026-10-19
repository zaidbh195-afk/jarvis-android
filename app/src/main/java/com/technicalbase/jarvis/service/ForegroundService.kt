package com.technicalbase.jarvis.service

import android.app.*
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.technicalbase.jarvis.bridge.LocalServer
import com.technicalbase.jarvis.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.IOException // <--- THIS WAS MISSING

class ForegroundService : Service() {
    private var wakeWord: WakeWord? = null
    private var recorder: AudioRecorder? = null
    private var tts: TtsPlayer? = null
    private val http = OkHttpClient()
    private var isProcessing = false
    private var lastScreenText = ""
    private val handler = Handler(Looper.getMainLooper())
    private val screenMonitor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        recorder = AudioRecorder(this)
        tts = TtsPlayer(this)
        wakeWord = WakeWord(this) { onWakeWord() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification())
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (!LocalServer.isRunning()) LocalServer.start(applicationContext, 8766, "change-me-on-first-boot")
        if (!isProcessing) wakeWord?.start()
        
        // Start monitoring screen text
        startScreenMonitor()
        
        return START_STICKY
    }

    private fun startScreenMonitor() {
        screenMonitor.execute {
            while (true) {
                Thread.sleep(3000)
                try {
                    val svc = com.technicalbase.jarvis.service.AccessibilityService.instance ?: continue
                    val currentText = svc.readScreenText()
                    
                    if (currentText.isNotEmpty() && currentText != lastScreenText) {
                        lastScreenText = currentText
                        sendScreenTextToBrain(currentText)
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun sendScreenTextToBrain(text: String) {
        val json = JSONObject().apply { put("text", text) }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val req = Request.Builder().url("http://127.0.0.1:8765/api/check_screen").post(body).build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val resp = response.body?.string() ?: ""
                if (resp.contains("lockdown_triggered")) {
                    handler.post {
                        tts?.speak("Keyword detected. Internet locked for 30 minutes.")
                    }
                }
            }
        })
    }

    private fun onWakeWord() {
        if (isProcessing) return
        isProcessing = true
        wakeWord?.stop()
        
        Handler(Looper.getMainLooper()).postDelayed({
            Executors.newSingleThreadExecutor().execute {
                try {
                    val wav = recorder?.record(5)
                    if (wav != null && wav.length() > 1000) {
                        val reply = sendToBrain(wav)
                        if (reply.isNotEmpty()) Handler(Looper.getMainLooper()).post { tts?.speak(reply) }
                        wav.delete()
                    }
                } catch (e: Exception) {} finally {
                    Handler(Looper.getMainLooper()).postDelayed({
                        isProcessing = false
                        wakeWord?.start()
                    }, 2000)
                }
            }
        }, 300)
    }

    private fun sendToBrain(wav: java.io.File): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "v.wav", wav.asRequestBody("audio/wav".toMediaType())).build()
        val req = Request.Builder().url("http://127.0.0.1:8765/stt").header("X-Jarvis-Token", "change-me-on-first-boot").post(body).build()
        return try {
            val resp = http.newCall(req).execute()
            val o = JSONObject(resp.body?.string() ?: "{}")
            o.optJSONObject("reply")?.optString("text", "") ?: "No reply."
        } catch (e: Exception) { "Can't reach the brain." }
    }

    override fun onDestroy() { 
        wakeWord?.stop()
        tts?.shutdown()
        LocalServer.stop()
        screenMonitor.shutdownNow()
        super.onDestroy() 
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, "jarvis")
        .setContentTitle("Jarvis is running").setContentText("Monitoring screen & listening...")
        .setSmallIcon(android.R.drawable.presence_online).setOngoing(true).build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
