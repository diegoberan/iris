package ai.iris.node.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ai.iris.node.NodeService
import ai.iris.node.Prefs

private enum class SettingsRoute { MAIN, CONNECTION, PERMISSIONS, ABOUT }

/**
 * Settings as a small navigation host, following the ChatGPT/Claude Android
 * pattern: a main list of grouped rows, each opening a focused sub-screen.
 * Connection (the old form) and Permissions are submenus. No nav library --
 * a single route state with a back-chain that unwinds to the chat.
 */
@Composable
fun SettingsHost(prefs: Prefs, onExit: () -> Unit) {
    var route by remember { mutableStateOf(SettingsRoute.MAIN) }

    androidx.activity.compose.BackHandler {
        if (route == SettingsRoute.MAIN) onExit() else route = SettingsRoute.MAIN
    }

    when (route) {
        SettingsRoute.MAIN -> SettingsList(prefs, onOpen = { route = it }, onExit = onExit)
        SettingsRoute.CONNECTION -> ConnectionScreen(prefs, onBack = { route = SettingsRoute.MAIN })
        SettingsRoute.PERMISSIONS -> PermissionsScreen(onBack = { route = SettingsRoute.MAIN })
        SettingsRoute.ABOUT -> AboutScreen(onBack = { route = SettingsRoute.MAIN })
    }
}

// ── Shared chrome ─────────────────────────────────────────────────────

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Mono.background)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp, 16.dp, 16.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Mono.foreground, fontSize = 22.sp,
                modifier = Modifier.clickable { onBack() }.padding(12.dp, 4.dp))
            Text(title, color = Mono.foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp))
        }
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Mono.mutedForeground,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 8.dp)
    )
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    trailingColor: androidx.compose.ui.graphics.Color = Mono.mutedForeground,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(20.dp, 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).background(Mono.card, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Iris.amber, modifier = Modifier.size(18.dp)) }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, color = Mono.foreground, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = Mono.mutedForeground, fontSize = 12.sp)
            }
        }
        if (trailing != null) {
            Text(trailing, color = trailingColor, fontSize = 13.sp)
        }
        if (onClick != null) {
            Text("  ›", color = Mono.mutedForeground, fontSize = 18.sp)
        }
    }
}

// ── Main list ─────────────────────────────────────────────────────────

@Composable
private fun SettingsList(prefs: Prefs, onOpen: (SettingsRoute) -> Unit, onExit: () -> Unit) {
    val status by NodeService.status.collectAsState()
    val gateway = prefs.gatewayUrl.removePrefix("https://").removePrefix("http://")

    SettingsScaffold("Settings", onBack = onExit) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Connection")
            SettingRow(
                icon = Icons.Rounded.Cloud, title = "Gateway",
                subtitle = gateway.ifBlank { "Not configured" },
                trailing = statusShort(status), trailingColor = statusColor(status),
                onClick = { onOpen(SettingsRoute.CONNECTION) }
            )

            SectionLabel("Device")
            SettingRow(
                icon = Icons.Rounded.Shield, title = "Permissions",
                subtitle = "What Íris can access on this phone",
                onClick = { onOpen(SettingsRoute.PERMISSIONS) }
            )

            SectionLabel("App")
            SettingRow(icon = Icons.Rounded.Info, title = "About", onClick = { onOpen(SettingsRoute.ABOUT) })

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onExit, modifier = Modifier.padding(start = 8.dp)) {
                Text("Back to chat", color = Mono.mutedForeground)
            }
        }
    }
}

private fun statusShort(status: String): String = when {
    status == "connected" -> "Connected"
    status.startsWith("error") -> "Error"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun statusColor(status: String) = when {
    status == "connected" -> Iris.amber
    status.startsWith("error") -> Mono.destructive
    else -> Mono.mutedForeground
}

// ── Connection ────────────────────────────────────────────────────────

@Composable
private fun ConnectionScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(prefs.gatewayUrl) }
    var user by remember { mutableStateOf(prefs.username) }
    var pass by remember { mutableStateOf(prefs.password) }
    val status by NodeService.status.collectAsState()

    SettingsScaffold("Connection", onBack = onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "This phone is a body for the Brain. Chat rides the same gateway " +
                    "connection that announces its capabilities.",
                color = Mono.mutedForeground, fontSize = 13.sp
            )
            SettingsField("Gateway URL", url) { url = it }
            SettingsField("Username", user) { user = it }
            SettingsField("Password", pass, password = true) { pass = it }

            Button(
                onClick = {
                    prefs.gatewayUrl = url; prefs.username = user; prefs.password = pass
                    NodeService.stop(context); NodeService.start(context)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Iris.amber, contentColor = Iris.onAmber)
            ) { Text("Save & Connect", fontWeight = FontWeight.SemiBold) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(statusColor(status), CircleShape))
                Text("  Status: $status", color = Mono.foreground, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = Mono.mutedForeground) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Mono.card, unfocusedContainerColor = Mono.card,
            focusedBorderColor = Iris.amber, unfocusedBorderColor = Mono.border,
            focusedTextColor = Mono.foreground, unfocusedTextColor = Mono.foreground,
            cursorColor = Iris.amber
        )
    )
}

// ── Permissions ───────────────────────────────────────────────────────

private data class CapPermission(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
    val permission: String,
    val minSdk: Int = 0,
)

@Composable
private fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Re-read status when returning from the system settings screen (no result
    // callback for that path) and after each in-app request.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    // Each capability the phone body can offer the Brain, and the Android
    // permission it needs. Denying one makes that capability announce as
    // unavailable -- the Brain simply won't route it here.
    val caps = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(CapPermission(Icons.Rounded.Notifications, "Notifications",
                "Let the Brain reach you with a push message.",
                Manifest.permission.POST_NOTIFICATIONS, minSdk = 33))
        }
        add(CapPermission(Icons.Rounded.LocationOn, "Location",
            "Answer 'where am I' with this phone's GPS fix.",
            Manifest.permission.ACCESS_FINE_LOCATION))
        add(CapPermission(Icons.Rounded.Mic, "Microphone",
            "Dictate messages by voice (speech to text).",
            Manifest.permission.RECORD_AUDIO))
        add(CapPermission(Icons.Rounded.CameraAlt, "Camera",
            "Attach a photo taken with the camera.",
            Manifest.permission.CAMERA))
    }

    SettingsScaffold("Permissions", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()))  {
            Text(
                "Iris is a body for the Brain. Each capability needs a phone " +
                    "permission — turn off any you don't want, and the Brain " +
                    "just won't use it.",
                color = Mono.mutedForeground, fontSize = 13.sp,
                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 4.dp)
            )

            refresh.let { // read to re-run status checks on bump
                caps.forEach { cap ->
                    val granted = ContextCompat_checkSelfPermission(context, cap.permission)
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp, 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp).background(
                                if (granted) Iris.amberSoft else Mono.card, RoundedCornerShape(9.dp)
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                cap.icon, null,
                                tint = if (granted) Iris.amber else Mono.mutedForeground,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(cap.title, color = Mono.foreground, fontSize = 15.sp)
                            Text(cap.description, color = Mono.mutedForeground, fontSize = 12.sp)
                        }
                        if (granted) {
                            Text("Granted", color = Iris.amber, fontSize = 13.sp)
                        } else {
                            TextButton(onClick = { requestLauncher.launch(cap.permission) }) {
                                Text("Allow", color = Iris.blue, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "To revoke a permission, open Android app settings.",
                color = Mono.mutedForeground, fontSize = 12.sp,
                modifier = Modifier.padding(20.dp, 4.dp)
            )
            TextButton(
                onClick = { openAppSettings(context) },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Open Android app settings", color = Iris.blue) }
        }
    }
}

private fun ContextCompat_checkSelfPermission(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

// ── About ─────────────────────────────────────────────────────────────

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
    val status by NodeService.status.collectAsState()

    SettingsScaffold("About", onBack = onBack) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Íris", color = Mono.foreground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Text(
                "One Brain. Multiple Bodies. This phone is an Íris Node — a body " +
                    "for a persistent Hermes agent, over the capability protocol.",
                color = Mono.mutedForeground, fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            InfoLine("Version", version)
            InfoLine("Node status", status)
            InfoLine("Capabilities", "chat · notification · location")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Mono.mutedForeground, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Mono.foreground, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
