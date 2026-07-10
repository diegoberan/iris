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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** One screen: gateway credentials, connect/disconnect, live Node status.
 *  The Node itself is NodeService — this activity only configures and
 *  observes it, so the connection survives the activity being killed. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { NodeScreen() } }
        }
    }
}

@Composable
private fun NodeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(context) }

    var url by remember { mutableStateOf(prefs.gatewayUrl) }
    var user by remember { mutableStateOf(prefs.username) }
    var pass by remember { mutableStateOf(prefs.password) }
    val status by NodeService.status.collectAsState()
    val lastEvent by NodeService.lastEvent.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Iris Node", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This phone is a body for the Brain: it announces notification " +
                "and location capabilities over the gateway WebSocket.",
            style = MaterialTheme.typography.bodyMedium
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
                if (status == "disconnected") NodeService.start(context) else NodeService.stop(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (status == "disconnected") "Connect" else "Disconnect")
        }

        Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
        if (lastEvent.isNotEmpty()) {
            Text("Last action: $lastEvent", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
