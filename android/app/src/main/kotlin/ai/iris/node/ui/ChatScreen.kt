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
import androidx.compose.material3.HorizontalDivider
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
                            Text("☰", color = Mono.foreground, fontSize = 20.sp)
                        }
                    },
                    title = {
                        Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Text("⚙", color = Mono.mutedForeground, fontSize = 18.sp)
                        }
                    }
                )
            },
            bottomBar = {
                Composer(
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
        if (message.text.isNotBlank() || message.streaming) {
            Text(
                message.text + if (message.streaming) " ▍" else "",
                color = Mono.foreground,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontFamily = Sans,
                modifier = Modifier.fillMaxWidth()
            )
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
    Column(
        Modifier
            .background(Mono.background)
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Thin strip: model chip + live tier / error notice.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Mono.card)
                    .border(1.dp, Mono.border, RoundedCornerShape(50))
                    .clickable { onModelTap() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).background(Iris.amber, CircleShape))
                Text(
                    modelLabel.ifBlank { "model" },
                    color = Mono.secondaryForeground,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).widthInChip()
                )
                Text(" ⌄", color = Mono.mutedForeground, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            val notice = errorNotice.ifBlank { tierNotice }
            if (notice.isNotBlank()) {
                Text(
                    notice,
                    color = if (errorNotice.isNotBlank()) Mono.destructive else Mono.mutedForeground,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("What's on your mind?", color = Mono.mutedForeground) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Mono.card,
                    unfocusedContainerColor = Mono.card,
                    focusedBorderColor = Iris.amber,
                    unfocusedBorderColor = Mono.border,
                    focusedTextColor = Mono.foreground,
                    unfocusedTextColor = Mono.foreground,
                    cursorColor = Iris.amber
                )
            )
            Box(
                Modifier
                    .padding(start = 8.dp, bottom = 4.dp)
                    .size(44.dp)
                    .background(if (busy) Mono.destructive else Iris.amber, CircleShape)
                    .clickable { if (busy) onStop() else onSend() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) "■" else "↑",
                    color = if (busy) Color.White else Iris.onAmber,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun Modifier.widthInChip(): Modifier = this.then(Modifier.width(140.dp))

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
