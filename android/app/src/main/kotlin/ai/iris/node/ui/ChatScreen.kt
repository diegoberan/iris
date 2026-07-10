package ai.iris.node.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.iris.node.ChatViewModel
import ai.iris.node.NodeService
import kotlinx.coroutines.launch

private val Sans = androidx.compose.ui.text.font.FontFamily.SansSerif

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val sessions by vm.sessions.collectAsState()
    val activeId by vm.activeSessionId.collectAsState()
    val title by vm.activeTitle.collectAsState()
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val model by vm.currentModel.collectAsState()
    val tierNotice by vm.tierNotice.collectAsState()
    val errorNotice by vm.errorNotice.collectAsState()
    val status by NodeService.status.collectAsState()

    var showModels by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // System back closes the drawer (or the model sheet) before it can
    // close the app -- matching every drawer-based Android app.
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen || showModels) {
        if (showModels) showModels = false else scope.launch { drawer.close() }
    }

    LaunchedEffect(messages.size, (messages.lastOrNull()?.text ?: "").length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Mono.sidebarBackground) {
                Text(
                    "Iris",
                    Modifier.padding(20.dp, 24.dp, 20.dp, 8.dp),
                    color = Mono.foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vm.newSession(); scope.launch { drawer.close() }
                    }.padding(20.dp, 12.dp)
                ) {
                    Text("+  New chat", color = Mono.foreground, fontSize = 15.sp)
                }
                Divider(color = Mono.sidebarBorder)
                LazyColumn {
                    items(sessions, key = { it.id }) { row ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (row.id == activeId) Mono.accent else Mono.sidebarBackground)
                                .clickable { vm.selectSession(row.id); scope.launch { drawer.close() } }
                                .padding(20.dp, 10.dp)
                        ) {
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
                        Column {
                            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (model.isNotEmpty()) model.substringAfterLast('/') else status,
                                color = Mono.mutedForeground, fontSize = 11.sp,
                                modifier = Modifier.clickable { vm.loadModelOptions(); showModels = true }
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Text("⚙", color = Mono.mutedForeground, fontSize = 18.sp)
                        }
                    }
                )
            },
            bottomBar = {
                Column(Modifier.background(Mono.background).imePadding()) {
                    if (tierNotice.isNotEmpty()) {
                        Text(
                            tierNotice, color = Mono.mutedForeground, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    if (errorNotice.isNotEmpty()) {
                        Text(
                            errorNotice, color = Mono.destructive, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp, 8.dp, 12.dp, 12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message Hermes…", color = Mono.mutedForeground) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5,
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Mono.card,
                                unfocusedContainerColor = Mono.card,
                                focusedBorderColor = Mono.ring,
                                unfocusedBorderColor = Mono.border,
                                focusedTextColor = Mono.foreground,
                                unfocusedTextColor = Mono.foreground,
                                cursorColor = Mono.foreground
                            )
                        )
                        Box(
                            Modifier
                                .padding(start = 8.dp, bottom = 4.dp)
                                .background(
                                    if (busy) Mono.destructive else Mono.primary,
                                    RoundedCornerShape(50)
                                )
                                .clickable {
                                    if (busy) {
                                        vm.interrupt()
                                    } else if (draft.isNotBlank()) {
                                        vm.sendPrompt(draft.trim()); draft = ""
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                if (busy) "■" else "↑",
                                color = if (busy) Color.White else Mono.primaryForeground,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        ) { inner ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages.size) { index ->
                    val message = messages[index]
                    if (message.role == "user") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                message.text,
                                color = Mono.foreground,
                                fontSize = 15.sp,
                                fontFamily = Sans,
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .background(Mono.userBubble, RoundedCornerShape(16.dp))
                                    .border(1.dp, Mono.userBubbleBorder, RoundedCornerShape(16.dp))
                                    .padding(12.dp, 8.dp)
                            )
                        }
                    } else {
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
                if (busy && messages.lastOrNull()?.streaming != true) {
                    // Turn accepted, first token not here yet.
                    item(key = "thinking") {
                        Text("thinking…", color = Mono.mutedForeground, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (showModels) {
        val providers by vm.providers.collectAsState()
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            containerColor = Mono.popover
        ) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                providers.forEach { provider ->
                    item {
                        Text(
                            provider.name,
                            color = Mono.mutedForeground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp)
                        )
                    }
                    items(provider.models, key = { "${provider.slug}:$it" }) { modelId ->
                        val isCurrent = modelId == model
                        Text(
                            modelId,
                            color = if (isCurrent) Mono.primaryForeground else Mono.foreground,
                            fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (isCurrent) Mono.primary else Mono.popover)
                                .clickable {
                                    vm.switchModel(provider.slug, modelId)
                                    showModels = false
                                }
                                .padding(20.dp, 10.dp)
                        )
                    }
                }
            }
        }
    }
}
