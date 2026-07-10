package ai.iris.node

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.iris.node.ui.ChatScreen
import ai.iris.node.ui.IrisTheme
import ai.iris.node.ui.SettingsHost

/** Chat-first: opens straight into the conversation when credentials exist,
 *  with Settings one tap away. The Node connection itself is owned by
 *  NodeService and survives this activity. */
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
    // First run (no credentials) drops straight into Settings; otherwise chat.
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
        if (prefs.username.isNotEmpty() && prefs.password.isNotEmpty()) {
            NodeService.start(context)
        }
    }

    if (showSettings) {
        SettingsHost(prefs, onExit = { showSettings = false })
    } else {
        ChatScreen(vm, onOpenSettings = { showSettings = true })
    }
}
