package ai.iris.node.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import java.io.File

/**
 * Minimal push-to-talk recorder for dictation: records an AAC/m4a clip while
 * held, then hands back a base64 data URL for the Brain's Whisper endpoint.
 * m4a keeps clips small and is accepted by the transcribe API's mime sniff.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    fun start(): Boolean = runCatching {
        val file = File(context.cacheDir, "iris-dictation.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = rec
        outFile = file
        true
    }.getOrDefault(false)

    /** Stop recording; returns a `data:audio/mp4;base64,...` URL, or null. */
    fun stop(): String? {
        val rec = recorder ?: return null
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
        val file = outFile ?: return null
        if (!file.exists() || file.length() < 512) return null // too short to be speech
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:audio/mp4;base64,$b64"
    }

    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
}
