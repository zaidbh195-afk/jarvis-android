package com.technicalbase.jarvis

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Log
import com.rementia.openwakeword.lib.DetectionMode
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

// --- WakeWord ---
class WakeWord(private val context: Context, private val onWakeWord: () -> Unit) {
    private var engine: WakeWordEngine? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    fun start() {
        if (engine != null) return
        try {
            val models = listOf(WakeWordModel("Jarvis", "jarvis.onnx", threshold = 0.5f))
            engine = WakeWordEngine(context, models, DetectionMode.SINGLE_BEST)
            engine?.start()
            scope.launch {
                engine?.detections?.collect { 
                    Log.i("Jarvis.Wake", "Detected!")
                    onWakeWord() 
                }
            }
        } catch (e: Exception) { Log.e("Jarvis.Wake", "Failed: ${e.message}") }
    }
    fun stop() { 
        try { engine?.release() } catch (_: Exception) {}
        engine = null 
    }
}

// --- AudioRecorder ---
class AudioRecorder(private val context: Context) {
    fun record(maxSeconds: Int = 5): File? {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        val file = File(context.cacheDir, "jarvis_voice.wav")
        val out = FileOutputStream(file)
        
        try {
            out.write(ByteArray(44))
            recorder.startRecording()
            val buf = ByteArray(minBuf)
            var totalBytes = 0
            var silentFrames = 0
            var hasSpoken = false
            
            while (totalBytes < 16000 * 2 * maxSeconds) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) {
                    out.write(buf, 0, n)
                    totalBytes += n
                    var maxAmp = 0
                    for (i in 0 until n step 2) { 
                        val amp = Math.abs(buf[i].toInt()); if (amp > maxAmp) maxAmp = amp 
                    }
                    if (maxAmp > 2000) { hasSpoken = true; silentFrames = 0 }
                    else if (hasSpoken) { silentFrames++; if (silentFrames > minBuf / 4) break }
                }
            }
            recorder.stop(); out.close()
            
            val dataSize = totalBytes
            val header = byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), (36 + dataSize).toByte(), ((36 + dataSize) shr 8).toByte(), ((36 + dataSize) shr 16).toByte(), ((36 + dataSize) shr 24).toByte(),
                'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(), 'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), 16, 0, 0, 0, 1, 0, 1, 0,
                16000.toByte(), (16000 shr 8).toByte(), (16000 shr 16).toByte(), (16000 shr 24).toByte(), 32, 0, 0, 0, 2, 0, 16, 0,
                'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), dataSize.toByte(), (dataSize shr 8).toByte(), (dataSize shr 16).toByte(), (dataSize shr 24).toByte()
            )
            val raf = java.io.RandomAccessFile(file, "rw"); raf.seek(0); raf.write(header); raf.close()
            return file
        } catch (e: Exception) { return null }
    }
}

// --- TtsPlayer ---
class TtsPlayer(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("en-IN")
                ready = true
            }
        }
    }
    fun speak(text: String) { if (ready) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j") }
    fun shutdown() { tts?.shutdown() }
}
