package ai.iris.node.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The Hermes Desktop "Mono" theme (dark), verbatim from
 * apps/desktop/src/themes/presets.ts -- clean grayscale, minimal and focused.
 * Extra roles beyond Material's scheme (user bubble, sidebar) are exposed as
 * Mono.* constants so the chat can match the Desktop's chat surfaces exactly.
 */
object Mono {
    val background = Color(0xFF0E0E0E)
    val foreground = Color(0xFFEAEAEA)
    val card = Color(0xFF141414)
    val muted = Color(0xFF1E1E1E)
    val mutedForeground = Color(0xFF808080)
    val popover = Color(0xFF181818)
    val primary = Color(0xFFEAEAEA)
    val primaryForeground = Color(0xFF0E0E0E)
    val secondary = Color(0xFF262626)
    val secondaryForeground = Color(0xFFC8C8C8)
    val accent = Color(0xFF222222)
    val accentForeground = Color(0xFFD8D8D8)
    val border = Color(0xFF2A2A2A)
    val ring = Color(0xFF9A9A9A)
    val destructive = Color(0xFFA84040)
    val sidebarBackground = Color(0xFF0A0A0A)
    val sidebarBorder = Color(0xFF202020)
    val userBubble = Color(0xFF1A1A1A)
    val userBubbleBorder = Color(0xFF363636)
}

/**
 * Iris brand accent, sampled from the app logo (a neural iris): warm amber
 * glowing from the pupil, cool steel-blue at the rim. Used sparingly over the
 * Mono grayscale base so the app reads as "Iris" without abandoning the
 * Desktop's Mono theme -- send button, live-stream cursor, active session bar,
 * running spinner, audio scrubber.
 */
object Iris {
    val amber = Color(0xFFE0A03A)
    val amberDim = Color(0xFF6E5220)
    val amberSoft = Color(0x22E0A03A)
    val blue = Color(0xFF5A7FB5)
    val onAmber = Color(0xFF0E0E0E)
}

private val MonoDarkScheme = darkColorScheme(
    background = Mono.background,
    onBackground = Mono.foreground,
    surface = Mono.card,
    onSurface = Mono.foreground,
    surfaceVariant = Mono.muted,
    onSurfaceVariant = Mono.mutedForeground,
    primary = Mono.primary,
    onPrimary = Mono.primaryForeground,
    secondary = Mono.secondary,
    onSecondary = Mono.secondaryForeground,
    tertiary = Mono.accent,
    onTertiary = Mono.accentForeground,
    outline = Mono.border,
    outlineVariant = Mono.sidebarBorder,
    error = Mono.destructive,
    surfaceContainer = Mono.popover,
    surfaceContainerLow = Mono.card,
    surfaceContainerHigh = Mono.popover
)

@Composable
fun IrisTheme(content: @Composable () -> Unit) {
    // The user runs the Desktop in dark Mono; the app commits to it too.
    isSystemInDarkTheme() // (kept for a future light variant)
    MaterialTheme(colorScheme = MonoDarkScheme, content = content)
}
