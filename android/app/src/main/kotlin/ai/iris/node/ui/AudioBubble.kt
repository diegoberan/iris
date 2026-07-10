package ai.iris.node.ui

import android.media.MediaPlayer
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.iris.node.GatewayConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlaybackState(
    val key: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

/** One shared player: starting a new voice message stops the previous one. */
private object AudioPlayback {
    val state = MutableStateFlow(PlaybackState())

    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    fun play(key: String, file: File) {
        release()
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener {
                state.value = state.value.copy(playing = false, positionMs = 0)
                stopTicker()
            }
            start()
        }
        player = mp
        state.value = PlaybackState(key, playing = true, positionMs = 0, durationMs = mp.duration)
        startTicker()
    }

    fun toggle() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause(); state.value = state.value.copy(playing = false); stopTicker()
        } else {
            mp.start(); state.value = state.value.copy(playing = true); startTicker()
        }
    }

    fun seekTo(fraction: Float) {
        val mp = player ?: return
        val target = (mp.duration * fraction).toInt()
        mp.seekTo(target)
        state.value = state.value.copy(positionMs = target)
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (true) {
                player?.let { mp -> runCatching { state.value = state.value.copy(positionMs = mp.currentPosition) } }
                delay(200)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private fun release() {
        stopTicker()
        runCatching { player?.stop(); player?.release() }
        player = null
        state.value = PlaybackState()
    }
}

private fun formatMs(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Voice-reply player card, matching the Desktop audio bubble: a filename
 * header, then a circular play/pause, elapsed/total time and a thin seekable
 * progress bar. TTS output was synthesized on the Desktop body's GPU and plays
 * here on the phone body -- capability composition across Nodes.
 */
@Composable
fun AudioBubble(mediaPath: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback by AudioPlayback.state.collectAsState()
    var loading by remember(mediaPath) { mutableStateOf(false) }
    var failed by remember(mediaPath) { mutableStateOf(false) }

    val isMine = playback.key == mediaPath
    val playing = isMine && playback.playing
    val fraction = if (isMine && playback.durationMs > 0) playback.positionMs.toFloat() / playback.durationMs else 0f
    val filename = mediaPath.substringAfterLast('/')

    fun onPlayTap() {
        when {
            loading -> {}
            isMine -> AudioPlayback.toggle()
            else -> {
                loading = true; failed = false
                scope.launch {
                    val ok = fetchAndPlay(mediaPath, context.cacheDir)
                    loading = false; failed = !ok
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth(0.92f)
            .background(Mono.card, RoundedCornerShape(14.dp))
            .border(1.dp, Mono.border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            filename,
            color = Mono.mutedForeground,
            fontSize = 12.sp,
            fontFamily = IbmPlexMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(if (failed) Mono.muted else Iris.amber, CircleShape)
                    .clickable { onPlayTap() },
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        color = Iris.onAmber, strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = when {
                            failed -> Icons.Rounded.ErrorOutline
                            playing -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = if (failed) Mono.destructive else Iris.onAmber,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                if (failed) "unavailable"
                else if (isMine) "${formatMs(playback.positionMs)} / ${formatMs(playback.durationMs)}"
                else "voice message",
                color = Mono.secondaryForeground,
                fontSize = 12.sp,
                fontFamily = IbmPlexMono,
                modifier = Modifier.padding(start = 12.dp)
            )
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = { if (isMine) AudioPlayback.seekTo(it) },
                enabled = isMine,
                colors = SliderDefaults.colors(
                    thumbColor = Iris.amber,
                    activeTrackColor = Iris.amber,
                    inactiveTrackColor = Mono.secondary,
                    disabledActiveTrackColor = Mono.secondary,
                    disabledInactiveTrackColor = Mono.secondary,
                    disabledThumbColor = Mono.mutedForeground
                ),
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
        }
    }
}

private suspend fun fetchAndPlay(mediaPath: String, cacheDir: File): Boolean {
    val rest = GatewayConnection.rest ?: return false
    return runCatching {
        val suffix = mediaPath.substringAfterLast('.', "wav")
        val file = File(cacheDir, "iris-voice-${mediaPath.hashCode()}.$suffix")
        if (!file.exists()) {
            val dataUrl = rest.fetchMediaDataUrl(mediaPath) ?: return false
            val base64 = dataUrl.substringAfter("base64,", "")
            if (base64.isEmpty()) return false
            withContext(Dispatchers.IO) { file.writeBytes(Base64.decode(base64, Base64.DEFAULT)) }
        }
        withContext(Dispatchers.Main) { AudioPlayback.play(mediaPath, file) }
        true
    }.getOrDefault(false)
}
