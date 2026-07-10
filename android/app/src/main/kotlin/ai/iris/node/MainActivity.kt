package ai.iris.node

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.iris.node.ui.ChatScreen
import ai.iris.node.ui.IrisTheme
import ai.iris.node.ui.Mono

/** Chat-first: opens straight into the conversation when credentials exist,
 *  with the connection settings one tap away. The Node connection itself is
 *  owned by NodeService and survives this activity. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IrisTheme { Surface(Modifier.fillMaxSize()) { Root() } }
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var showSettings by remember { mutableStateOf(prefs.username.isEmpty() || prefs.password.isEmpty()) }
    val vm: ChatViewModel = viewModel()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
        // Auto-connect when already configured.
        if (prefs.username.isNotEmpty() && prefs.password.isNotEmpty()) {
            NodeService.start(context)
        }
    }

    if (showSettings) {
        SettingsScreen(prefs, onDone = { showSettings = false })
    } else {
        ChatScreen(vm, onOpenSettings = { showSettings = true })
    }
}

@Composable
private fun SettingsScreen(prefs: Prefs, onDone: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(prefs.gatewayUrl) }
    var user by remember { mutableStateOf(prefs.username) }
    var pass by remember { mutableStateOf(prefs.password) }
    val status by NodeService.status.collectAsState()
    val lastEvent by NodeService.lastEvent.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Iris Node", style = MaterialTheme.typography.headlineMedium, color = Mono.foreground)
        Text(
            "This phone is a body for the Brain: chat rides the same gateway " +
                "connection that announces notification and location capabilities.",
            style = MaterialTheme.typography.bodyMedium,
            color = Mono.mutedForeground
        )

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Gateway URL") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Username") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        Button(
            onClick = {
                prefs.gatewayUrl = url
                prefs.username = user
                prefs.password = pass
                NodeService.stop(context)
                NodeService.start(context)
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save & Connect")
        }

        TextButton(onClick = onDone) { Text("Back to chat", color = Mono.mutedForeground) }

        Text("Status: $status", style = MaterialTheme.typography.bodyLarge, color = Mono.foreground)
        if (lastEvent.isNotEmpty()) {
            Text("Last action: $lastEvent", style = MaterialTheme.typography.bodyMedium, color = Mono.mutedForeground)
        }
    }
}
