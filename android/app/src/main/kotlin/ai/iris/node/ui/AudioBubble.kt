package ai.iris.node.ui

import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.iris.node.GatewayConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One shared player so a new voice message stops the previous one. */
private object AudioPlayback {
    private var player: MediaPlayer? = null
    private var onStop: (() -> Unit)? = null

    fun play(file: File, onDone: () -> Unit) {
        stop()
        onStop = onDone
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
        onStop?.invoke()
        onStop = null
    }
}

/** Voice-reply bubble: the Desktop's audio pill, Mono-styled. Fetches the
 *  gateway-local file through /api/media (base64 data URL) on first play. */
@Composable
fun AudioBubble(mediaPath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(mediaPath) { mutableStateOf("idle") } // idle|loading|playing|error

    DisposableEffect(mediaPath) { onDispose { if (state == "playing") AudioPlayback.stop() } }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Mono.card, RoundedCornerShape(20.dp))
            .border(1.dp, Mono.border, RoundedCornerShape(20.dp))
            .clickable(enabled = state != "loading") {
                if (state == "playing") {
                    AudioPlayback.stop()
                    state = "idle"
                    return@clickable
                }
                state = "loading"
                scope.launch {
                    val ok = prepareAndPlay(mediaPath, context.cacheDir) { state = "idle" }
                    state = if (ok) "playing" else "error"
                }
            }
            .padding(14.dp, 10.dp)
    ) {
        Text(
            when (state) {
                "playing" -> "◼"
                "loading" -> "…"
                "error" -> "!"
                else -> "▶"
            },
            color = if (state == "error") Mono.destructive else Mono.foreground,
            fontSize = 16.sp
        )
        Text(
            when (state) {
                "error" -> "audio unavailable"
                else -> "Voice message"
            },
            color = Mono.secondaryForeground,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

private suspend fun prepareAndPlay(mediaPath: String, cacheDir: File, onDone: () -> Unit): Boolean {
    val rest = GatewayConnection.rest ?: return false
    return runCatching {
        val dataUrl = rest.fetchMediaDataUrl(mediaPath) ?: return false
        val base64 = dataUrl.substringAfter("base64,", "")
        if (base64.isEmpty()) return false
        withContext(Dispatchers.IO) {
            val suffix = mediaPath.substringAfterLast('.', "wav")
            val file = File(cacheDir, "iris-voice-${mediaPath.hashCode()}.$suffix")
            if (!file.exists()) file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
            AudioPlayback.play(file, onDone)
        }
        true
    }.getOrDefault(false)
}
