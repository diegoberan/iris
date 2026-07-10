package ai.iris.node.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.iris.node.ActivityRow
import ai.iris.node.ChatMessage
import ai.iris.node.ChatViewModel
import ai.iris.node.NodeService
import ai.iris.node.R
import kotlinx.coroutines.launch

private val Sans = FontFamily.SansSerif

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val sessions by vm.sessions.collectAsState()
    val activeId by vm.activeSessionId.collectAsState()
    val title by vm.activeTitle.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val busy by vm.busy.collectAsState()
    val model by vm.currentModel.collectAsState()
    val tierNotice by vm.tierNotice.collectAsState()
    val errorNotice by vm.errorNotice.collectAsState()
    val status by NodeService.status.collectAsState()

    var showModels by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    androidx.activity.compose.BackHandler(enabled = drawer.isOpen || showModels) {
        if (showModels) showModels = false else scope.launch { drawer.close() }
    }

    LaunchedEffect(transcript.size, (transcript.lastOrNull() as? ChatMessage)?.text?.length ?: 0) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            DrawerContent(
                sessions = sessions,
                activeId = activeId,
                onNew = { vm.newSession(); scope.launch { drawer.close() } },
                onSelect = { vm.selectSession(it); scope.launch { drawer.close() } }
            )
        }
    ) {
        Scaffold(
            containerColor = Mono.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Mono.background,
                        titleContentColor = Mono.foreground
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Rounded.Menu, "Sessions", tint = Mono.foreground)
                        }
                    },
                    title = {
                        Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Rounded.Settings, "Settings", tint = Mono.mutedForeground)
                        }
                    }
                )
            },
            bottomBar = {
                Composer(
                    vm = vm,
                    draft = draft,
                    onDraft = { draft = it },
                    busy = busy,
                    modelLabel = if (model.isNotEmpty()) model.substringAfterLast('/') else status,
                    tierNotice = tierNotice,
                    errorNotice = errorNotice,
                    onModelTap = { vm.loadModelOptions(); showModels = true },
                    onSend = { if (draft.isNotBlank()) { vm.sendPrompt(draft.trim()); draft = "" } },
                    onStop = { vm.interrupt() }
                )
            }
        ) { inner ->
            if (transcript.isEmpty()) {
                EmptyState(Modifier.fillMaxSize().padding(inner))
                return@Scaffold
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(transcript, key = { it.key }) { item ->
                    when (item) {
                        is ChatMessage ->
                            if (item.role == "user") UserMessage(item.text)
                            else AssistantMessage(item)
                        is ActivityRow -> ActivityLine(item)
                    }
                }
                if (busy && transcript.none { it is ActivityRow && it.running } &&
                    (transcript.lastOrNull() as? ChatMessage)?.streaming != true
                ) {
                    item(key = "thinking-fallback") { ActivityLine(ActivityRow("live", "Thinking", running = true)) }
                }
            }
        }
    }

    if (showModels) {
        ModelSheet(vm, model, onDismiss = { showModels = false })
    }
}

// ── Empty state ───────────────────────────────────────────────────────

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.iris_wordmark),
            contentDescription = "Iris",
            modifier = Modifier.fillMaxWidth(0.55f)
        )
        Text(
            "One Brain. Multiple Bodies.",
            color = Mono.mutedForeground,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

// ── Transcript pieces ─────────────────────────────────────────────────

@Composable
private fun UserMessage(text: String) {
    // Full-width subtle box, left-aligned -- the Desktop's user message look,
    // not a right-hand chat bubble.
    Text(
        text,
        color = Mono.foreground,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontFamily = Sans,
        modifier = Modifier
            .fillMaxWidth()
            .background(Mono.userBubble, RoundedCornerShape(12.dp))
            .border(1.dp, Mono.userBubbleBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
    )
}

@Composable
private fun AssistantMessage(message: ChatMessage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.mediaPath != null) AudioBubble(message.mediaPath)
        if (message.streaming) {
            // While tokens stream, render raw text + caret (markdown re-parse
            // per token is wasteful and half-open ** / ``` would flicker).
            Text(
                message.text + " ▍",
                color = Mono.foreground, fontSize = 15.sp, lineHeight = 22.sp,
                fontFamily = Sans, modifier = Modifier.fillMaxWidth()
            )
        } else if (message.text.isNotBlank()) {
            MarkdownText(message.text)
        }
    }
}

@Composable
private fun ActivityLine(row: ActivityRow) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (row.running) Iris.amber else Mono.mutedForeground, CircleShape)
        )
        val suffix = row.durationS?.let { " · ${"%.1f".format(it)}s" } ?: ""
        Text(
            row.label + suffix,
            color = Mono.mutedForeground,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

// ── Composer ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    vm: ChatViewModel,
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    modelLabel: String,
    tierNotice: String,
    errorNotice: String,
    onModelTap: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachments by vm.pendingAttachments.collectAsState()
    val dictated by vm.dictated.collectAsState()
    val dictating by vm.dictating.collectAsState()

    // Whisper transcript → append to the draft (dictation), then consume.
    LaunchedEffect(dictated) {
        if (dictated.isNotBlank()) {
            onDraft((draft.trimEnd() + " " + dictated).trim())
            vm.consumeDictation()
        }
    }

    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                vm.attachImage(b64, "image-${System.currentTimeMillis()}.jpg")
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { recording = recorder.start() } }

    fun toggleMic() {
        if (recording) {
            recording = false
            val dataUrl = recorder.stop()
            if (dataUrl != null) vm.transcribe(dataUrl, "audio/mp4")
        } else {
            val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) recording = recorder.start()
            else micPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val showSend = draft.isNotBlank() || busy

    // One rounded container (Claude/ChatGPT style): input on top, a control
    // row below with attach, model pill, mic and send.
    Column(
        Modifier
            .background(Mono.background)
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val notice = errorNotice.ifBlank { tierNotice }
        if (notice.isNotBlank()) {
            Text(
                notice,
                color = if (errorNotice.isNotBlank()) Mono.destructive else Mono.mutedForeground,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Mono.card)
                .border(1.dp, Mono.border, RoundedCornerShape(24.dp))
                .padding(6.dp)
        ) {
            if (attachments.isNotEmpty()) {
                Text(
                    "📎 " + attachments.joinToString(", "),
                    color = Mono.mutedForeground, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Message Iris…", color = Mono.mutedForeground) },
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Mono.foreground,
                    unfocusedTextColor = Mono.foreground,
                    cursorColor = Iris.amber
                )
            )

            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach image
                RoundIconButton(Icons.Rounded.Add, "Attach", bg = Mono.muted, tint = Mono.foreground) {
                    imagePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Model pill
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Mono.muted)
                        .clickable { onModelTap() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(Iris.amber, CircleShape))
                    Text(
                        modelLabel.ifBlank { "model" },
                        color = Mono.secondaryForeground, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp).width(120.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                // Mic (dictation)
                RoundIconButton(
                    Icons.Rounded.Mic,
                    "Voice input",
                    bg = if (recording) Iris.amber else Mono.muted,
                    tint = if (recording) Iris.onAmber else Mono.foreground,
                    loading = dictating
                ) { toggleMic() }
                Spacer(Modifier.width(8.dp))
                // Send / stop (shown when there's something to do)
                if (showSend) {
                    Box(
                        Modifier.size(40.dp)
                            .background(if (busy) Mono.destructive else Iris.amber, CircleShape)
                            .clickable { if (busy) onStop() else onSend() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (busy) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                            contentDescription = if (busy) "Stop" else "Send",
                            tint = if (busy) Color.White else Iris.onAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    bg: Color,
    tint: Color,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).background(bg, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                color = tint, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)
            )
        } else {
            Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Drawer ────────────────────────────────────────────────────────────

@Composable
private fun DrawerContent(
    sessions: List<ai.iris.node.SessionRow>,
    activeId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter { it.title.contains(query, true) || it.preview.contains(query, true) }
    }

    ModalDrawerSheet(drawerContainerColor = Mono.sidebarBackground) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp, 24.dp, 20.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.iris_logo),
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(CircleShape)
            )
            Text(
                "Iris",
                Modifier.padding(start = 10.dp),
                color = Mono.foreground,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
        }

        Row(
            Modifier.fillMaxWidth().clickable { onNew() }.padding(20.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(20.dp).background(Iris.amberSoft, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) { Text("+", color = Iris.amber, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            Text("New session", color = Mono.foreground, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            placeholder = { Text("Search sessions…", color = Mono.mutedForeground, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Mono.card,
                unfocusedContainerColor = Mono.card,
                focusedBorderColor = Iris.amber,
                unfocusedBorderColor = Mono.sidebarBorder,
                focusedTextColor = Mono.foreground,
                unfocusedTextColor = Mono.foreground,
                cursorColor = Iris.amber
            )
        )

        Text(
            "SESSIONS  ${filtered.size}",
            color = Mono.mutedForeground,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 6.dp)
        )
        HorizontalDivider(color = Mono.sidebarBorder)

        LazyColumn {
            items(filtered, key = { it.id }) { row ->
                val active = row.id == activeId
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) Mono.accent else Mono.sidebarBackground)
                        .clickable { onSelect(row.id) }
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(3.dp).height(44.dp)
                            .background(if (active) Iris.amber else Color.Transparent)
                    )
                    Column(Modifier.padding(17.dp, 10.dp)) {
                        Text(
                            row.title, color = Mono.foreground, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (row.preview.isNotBlank()) {
                            Text(
                                row.preview, color = Mono.mutedForeground, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Model sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(vm: ChatViewModel, currentModel: String, onDismiss: () -> Unit) {
    val providers by vm.providers.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Mono.popover) {
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            providers.forEach { provider ->
                item(key = "hdr-${provider.slug}") {
                    Text(
                        provider.name,
                        color = Mono.mutedForeground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp)
                    )
                }
                items(provider.models, key = { "${provider.slug}:$it" }) { modelId ->
                    val isCurrent = modelId == currentModel
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.switchModel(provider.slug, modelId); onDismiss() }
                            .padding(20.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(6.dp)
                                .background(if (isCurrent) Iris.amber else Color.Transparent, CircleShape)
                        )
                        Text(
                            modelId,
                            color = if (isCurrent) Mono.foreground else Mono.secondaryForeground,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
