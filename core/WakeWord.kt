package com.technicalbase.jarvis.core

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.DetectionMode
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
