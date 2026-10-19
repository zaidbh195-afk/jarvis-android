package com.technicalbase.jarvis.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class WakeWord(private val context: Context, private val onWakeWord: () -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false

    fun start() {
        if (isListening) return
        isListening = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (isListening) {
                    // Auto-restart on silence or timeout
                    Thread.sleep(300)
                    start() 
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.joinToString(" ")?.lowercase() ?: ""
                if (text.contains("jarvis")) {
                    onWakeWord()
                    isListening = false
                } else {
                    if (isListening) start()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(intent)
    }

    fun stop() {
        isListening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}

class AudioRecorder(private val context: Context) {
    fun record(maxSeconds: Int = 8): File? {
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
            var hasSpoken = false
            var lastSpeechTime = System.currentTimeMillis()
            
            while (totalBytes < 16000 * 2 * maxSeconds) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) {
                    out.write(buf, 0, n)
                    totalBytes += n
                    
                    var maxAmp = 0
                    for (i in 0 until n step 2) { 
                        val amp = Math.abs(buf[i].toInt()); if (amp > maxAmp) maxAmp = amp 
                    }
                    
                    val now = System.currentTimeMillis()
                    if (maxAmp > 2000) { 
                        hasSpoken = true
                        lastSpeechTime = now
                    } else if (hasSpoken && (now - lastSpeechTime > 1500)) {
                        // 1.5 seconds of silence detected after speaking
                        break 
                    }
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