package com.technicalbase.jarvis.service

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import androidx.core.app.NotificationCompat
import com.technicalbase.jarvis.bridge.LocalServer
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.IOException

class ForegroundService : Service() {
    private var wakeWord: WakeWord? = null
    private var recorder: AudioRecorder? = null
    private var tts: TtsPlayer? = null
    private var toneGen: ToneGenerator? = null
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
        toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1001, buildNotification("Monitoring screen..."))
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (!LocalServer.isRunning()) LocalServer.start(applicationContext, 8766, "change-me-on-first-boot")
        if (!isProcessing) wakeWord?.start()
        
        startScreenMonitor()
        return START_STICKY
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, buildNotification(text))
    }

    // --- SINGLE SCREEN MONITOR FUNCTION ---
    private fun startScreenMonitor() {
        screenMonitor.execute {
            while (true) {
                Thread.sleep(2000)
                try {
                    val svc = AccessibilityService.instance ?: continue
                    val screenText = svc.readScreenText()
                    val focusedText = svc.readFocusedText()
                    val combinedText = "$screenText\n$focusedText".trim()
                    
                    if (combinedText.isNotEmpty() && combinedText != lastScreenText) {
                        lastScreenText = combinedText
                        sendScreenTextToBrain(combinedText)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("JarvisMonitor", "Screen read error: ${e.message}")
                }
            }
        }
    }

    private fun sendScreenTextToBrain(text: String) {
        val json = JSONObject().apply { put("text", text) }
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val req = Request.Builder().url("http://127.0.0.1:8765/api/check_screen").post(body).build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("JarvisMonitor", "Failed to reach brain: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val resp = response.body?.string() ?: ""
                if (resp.contains("lockdown_triggered")) {
                    handler.post { tts?.speak("Keyword detected. Internet locked for 30 minutes.") }
                }
            }
        })
    }

    private fun onWakeWord() {
        if (isProcessing) return
        isProcessing = true
        
        handler.post {
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            updateNotif("Listening to you...")
            wakeWord?.stop()
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            Executors.newSingleThreadExecutor().execute {
                try {
                    val wav = recorder?.record(8)
                    if (wav != null && wav.length() > 1000) {
                        handler.post { updateNotif("Thinking...") }
                        val reply = sendToBrain(wav)
                        
                        if (reply.isNotEmpty()) {
                            handler.post { 
                                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
                                updateNotif("Speaking...")
                                tts?.speak(reply) 
                            }
                        }
                        wav.delete()
                    }
                } catch (e: Exception) {} finally {
                    Handler(Looper.getMainLooper()).postDelayed({
                        isProcessing = false
                        handler.post { updateNotif("Monitoring screen...") }
                        wakeWord?.start()
                    }, 3000)
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
        toneGen?.release()
        LocalServer.stop()
        screenMonitor.shutdownNow()
        super.onDestroy() 
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, "jarvis")
        .setContentTitle("Jarvis AI")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.presence_online)
        .setOngoing(true)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
