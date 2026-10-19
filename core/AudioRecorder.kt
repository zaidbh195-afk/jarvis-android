package com.technicalbase.jarvis.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream

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
            
            // Fix WAV header
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
