package com.myagent.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AgentViewModel) {
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(vm.prefs.apiKey.isBlank()) }
    var input by rememberSaveable { mutableStateOf("") }
    var title by remember { mutableStateOf(vm.prefs.agentName) }
    var background by remember { mutableStateOf(vm.prefs.background) }
    var showHistory by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Bitmap?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val rowCount = (if (vm.items.isEmpty()) 1 else vm.items.size) + (if (vm.busy) 1 else 0)
    LaunchedEffect(rowCount) { listState.animateScrollToItem(rowCount - 1) }

    fun loadPhoto(uri: Uri) {
        try {
            pendingImage = Media.load(context, uri)
        } catch (e: Exception) {
            vm.showError("Couldn't open that photo: ${e.message}")
        }
    }

    // 🎤 Voice: Google's speech screen turns speech into text, then we send it.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
            vm.send(spoken, pendingImage, viaVoice = true)
            pendingImage = null
        }
    }
    fun startVoice() {
        vm.stopSpeaking()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to ${vm.prefs.agentName}")
        val lang = vm.prefs.voiceLanguage
        if (lang.isNotBlank()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.showError("Voice input isn't available. Install or update the Google app and try again.")
        }
    }

    // Hands-free voice conversation needs the microphone permission once.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoiceMode() else vm.showError("Voice mode needs microphone permission.")
    }
    fun startVoiceMode() {
        vm.stopSpeaking()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startVoiceMode()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 📷 Photos: gallery picker or camera.
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadPhoto(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) loadPhoto(uri)
    }

    val kittyStatus = when {
        vm.waitingForApproval -> "等你确认"
        vm.busy -> "思考中…"
        else -> "Here for you"
    }

    Box(Modifier.fillMaxSize()) {
    AppBackground(background, startPage = vm.items.isEmpty())
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = { vm.toggleSpeakReplies() }) {
                        SpeakerIcon(on = vm.speakReplies)
                    }
                    TextButton(onClick = { vm.newChat() }, enabled = vm.canSend) { Text("New") }
                    TextButton(onClick = { showHistory = true }, enabled = vm.canSend) { Text("History") }
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding()
        ) {
            if (vm.items.isNotEmpty()) KittyMascot(
                name = title,
                status = kittyStatus,
                busy = vm.busy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.items.isEmpty()) item {
                    StartPage(
                        name = title,
                        status = kittyStatus,
                        busy = vm.busy,
                        onAsk = { prompt -> if (vm.canSend) vm.send(prompt) },
                        onMls = {
                            if (!vm.mlsConnected) showSettings = true // connect first
                            else if (vm.canSend) vm.send(
                                "Check my MLS: summarize my current listings (price, status, days on market) " +
                                    "and any upcoming open houses."
                            )
                        },
                    )
                }
                items(vm.items) { ChatRow(it) }
                if (vm.busy) item {
                    Text(
                        "Working…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }

            pendingImage?.let { img ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = "Photo to send",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Text(
                        "Photo attached",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { pendingImage = null }) { Text("Remove") }
                }
            }

            if (vm.voiceState != AgentViewModel.VoiceState.OFF) {
                VoicePanel(vm)
            } else {
            // Rounded floating input bar
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(34.dp),
                color = Color.White.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, Color(0xFFDDEBFA)),
            ) {
            Row(
                Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box {
                    IconButton(onClick = { photoMenu = true }, enabled = vm.canSend) {
                        CameraIcon(enabled = vm.canSend)
                    }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Take a photo") },
                            onClick = {
                                photoMenu = false
                                val uri = Media.newCameraUri(context)
                                cameraUri = uri
                                try {
                                    cameraLauncher.launch(uri)
                                } catch (e: ActivityNotFoundException) {
                                    vm.showError("No camera app found.")
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Choose from gallery") },
                            onClick = {
                                photoMenu = false
                                pickLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                        )
                    }
                }
                Box(Modifier.width(1.dp).height(26.dp).background(Color(0xFFCFE3F7)))
                IconButton(onClick = { startVoice() }, enabled = vm.canSend) {
                    MicIcon(enabled = vm.canSend)
                }
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (pendingImage != null) "Ask about the photo" else "Ask me anything") },
                    maxLines = 5,
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFEEF5FD),
                        unfocusedContainerColor = Color(0xFFEEF5FD),
                        disabledContainerColor = Color(0xFFEEF5FD),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = SkyInk,
                        unfocusedTextColor = SkyInk,
                    ),
                )
                val canSendNow = vm.canSend && (input.isNotBlank() || pendingImage != null)
                // Nothing typed: the round button starts a hands-free voice conversation instead.
                val talkButton = vm.canSend && input.isBlank() && pendingImage == null
                // While the kitty is thinking, the same button becomes Stop.
                val stopButton = vm.busy || vm.waitingForApproval
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                if (stopButton) listOf(Color(0xFF3B5BA9), Color(0xFF13306B))
                                else if (canSendNow || talkButton) listOf(Color(0xFF6CB8FA), Color(0xFF3B8EEA))
                                else listOf(Color(0xFFBFDDF8), Color(0xFFA9D0F4))
                            )
                        )
                        .clickable(enabled = stopButton || canSendNow || talkButton) {
                            if (stopButton) {
                                vm.stopAgent()
                            } else if (talkButton) {
                                startVoiceMode()
                            } else {
                                vm.send(input.trim(), pendingImage)
                                input = ""
                                pendingImage = null
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        stopButton -> StopIcon()
                        talkButton -> WaveIcon()
                        else -> SendPlaneIcon()
                    }
                }
            }
            }
            }
        }
    }

    }

    if (showHistory) HistoryDialog(vm) { showHistory = false }

    if (showSettings) SettingsDialog(vm) {
        showSettings = false
        title = vm.prefs.agentName
        background = vm.prefs.background
    }
}

@Composable
private fun StartPage(name: String, status: String, busy: Boolean, onAsk: (String) -> Unit, onMls: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KittyHero(name, status, busy)
        Text(
            "What can I help you with?",
            color = DeepNavy,
            fontSize = 27.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StartCard("Organize my inbox", { MailIcon() }) {
                onAsk("Help me organize my inbox: find promotional and junk emails from the last month that I can clean up.")
            }
            StartCard("Plan my weekend", { CalendarIcon() }) {
                onAsk("Help me plan a fun, relaxing weekend. Check the weather first and suggest a few ideas.")
            }
            StartCard("Check my MLS", { HouseIcon() }, onMls)
        }
    }
}

@Composable
private fun StartCard(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color(0xFFDDEBFA)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color(0xFFEAF3FD)),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(
                label,
                color = DeepNavy,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 18.dp),
            )
            ChevronIcon()
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val context = LocalContext.current
    when (item) {
        is ChatItem.AgentImage -> Column(horizontalAlignment = Alignment.Start) {
            Image(
                bitmap = item.image.asImageBitmap(),
                contentDescription = "Picture from the kitty",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            TextButton(onClick = {
                val ok = Media.saveToGallery(context, item.image)
                android.widget.Toast.makeText(
                    context,
                    if (ok) "Saved to Photos (Pictures/猫猫小助手)" else "Couldn't save the picture",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }) { Text("Save to Photos") }
        }
        is ChatItem.User -> Bubble(item.text, mine = true, image = item.image)
        is ChatItem.Agent -> Bubble(item.text.replace("**", ""), mine = false)
        is ChatItem.Note -> Text(
            item.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is ChatItem.Error -> Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is ChatItem.Approval -> ApprovalCard(item.approval)
        is ChatItem.Unsubscribe -> UnsubscribeCard(item.approval)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, image: Bitmap? = null) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (mine) Color.White.copy(alpha = 0.92f) else Color(0xFFEAF6FF).copy(alpha = 0.85f),
            contentColor = SkyInk,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column {
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "Photo you sent",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 260.dp).padding(6.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                if (text.isNotBlank()) {
                    SelectionContainer {
                        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(a: TrashApproval) {
    val pending = a.status == TrashApproval.Status.PENDING
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Move these emails to Trash?", style = MaterialTheme.typography.titleMedium)
            if (a.reason.isNotBlank()) Text(a.reason, style = MaterialTheme.typography.bodyMedium)
            if (pending) Text(
                "Uncheck anything you want to keep.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            a.emails.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = a.selected[e.id] == true,
                        onCheckedChange = { a.selected[e.id] = it },
                        enabled = pending,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.from.substringBefore("<").trim().trim('"').ifBlank { e.from },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            e.subject,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider()
            when (a.status) {
                TrashApproval.Status.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.approve() }, enabled = a.selectedCount > 0) {
                        Text("Move ${a.selectedCount} to Trash")
                    }
                    OutlinedButton(onClick = { a.cancel() }) { Text("Cancel") }
                }
                TrashApproval.Status.WORKING -> Text("Moving to Trash…")
                TrashApproval.Status.DONE -> Text(a.resultText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun UnsubscribeCard(a: UnsubscribeApproval) {
    val pending = a.status == TrashApproval.Status.PENDING
    val uriHandler = LocalUriHandler.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Unsubscribe from these senders?", style = MaterialTheme.typography.titleMedium)
            if (a.reason.isNotBlank()) Text(a.reason, style = MaterialTheme.typography.bodyMedium)
            if (pending) Text(
                "Uncheck any you want to keep hearing from.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            a.targets.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.method == UnsubTarget.Method.LINK) {
                        Box(Modifier.size(48.dp))
                    } else {
                        Checkbox(
                            checked = a.selected[t.key] == true,
                            onCheckedChange = { a.selected[t.key] = it },
                            enabled = pending,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            a.results[t.key] ?: t.methodLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val page = t.pageUrl
                    val showOpen = t.method == UnsubTarget.Method.LINK ||
                        (a.status == TrashApproval.Status.DONE && a.results[t.key] == "Didn't go through")
                    if (page != null && showOpen) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(page) } }) { Text("Open page") }
                    }
                }
            }
            HorizontalDivider()
            when (a.status) {
                TrashApproval.Status.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.approve() }, enabled = a.selectedCount > 0) {
                        Text("Unsubscribe ${a.selectedCount}")
                    }
                    OutlinedButton(onClick = { a.cancel() }) { Text("Cancel") }
                }
                TrashApproval.Status.WORKING -> Text("Unsubscribing…")
                TrashApproval.Status.DONE -> Text(a.resultText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** List of saved conversations: tap to reopen, or delete. */
@Composable
private fun HistoryDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    var chats by remember { mutableStateOf<List<ChatStore.ChatInfo>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) { chats = vm.historyList() }
    val fmt = remember { java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    chats == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    chats!!.isEmpty() -> Text(
                        "No saved chats yet. Your conversations will appear here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> chats!!.forEach { c ->
                        Surface(
                            onClick = { vm.openChat(c); onDismiss() },
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFEAF5FF),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.title, color = DeepNavy, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(fmt.format(java.util.Date(c.updated)), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { vm.deleteChat(c.id); refresh++ }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(vm: AgentViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(vm.prefs.apiKey) }
    var model by remember { mutableStateOf(vm.prefs.model) }
    var name by remember { mutableStateOf(vm.prefs.agentName) }
    var voiceLang by remember { mutableStateOf(vm.prefs.voiceLanguage) }
    var showDetails by remember { mutableStateOf(vm.prefs.showDetails) }
    var bgChoice by remember { mutableStateOf(vm.prefs.background) }
    var learnFacts by remember { mutableStateOf(vm.prefs.learnFacts) }
    var memories by remember { mutableStateOf(vm.prefs.memories) }
    var aboutMe by remember { mutableStateOf(vm.prefs.aboutMe) }
    var newMemory by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(vm.prefs.recentActivity) }
    var mlsUrl by remember { mutableStateOf(vm.prefs.mlsUrl) }
    val voiceOptions = remember { vm.voiceOptions() }
    var voiceTabZh by remember { mutableStateOf(false) }
    var voiceEn by remember { mutableStateOf(vm.prefs.voiceEn) }
    var voiceZh by remember { mutableStateOf(vm.prefs.voiceZh) }
    var pitch by remember { mutableStateOf(vm.prefs.ttsPitch) }
    var rate by remember { mutableStateOf(vm.prefs.ttsRate) }
    var usageIn by remember { mutableStateOf(vm.prefs.usageIn) }
    var usageOut by remember { mutableStateOf(vm.prefs.usageOut) }
    var usageCalls by remember { mutableStateOf(vm.prefs.usageCalls) }
    var usageCostMicros by remember { mutableStateOf(vm.prefs.usageCostMicros) }
    var usageSince by remember { mutableStateOf(vm.prefs.usageSince) }
    val settingsContext = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("OpenAI API key") },
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("Model") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Agent name") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                )
                Text("Usage", style = MaterialTheme.typography.titleSmall)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFEAF5FF),
                    border = BorderStroke(1.dp, Color(0xFFBFDFF8)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        UsageLine("Total tokens", "%,d".format(usageIn + usageOut), bold = true)
                        UsageLine("In (what the kitty reads)", "%,d".format(usageIn))
                        UsageLine("Out (what the kitty writes)", "%,d".format(usageOut))
                        UsageLine("AI calls", "%,d".format(usageCalls))
                        UsageLine("Estimated cost", "$%.4f".format(usageCostMicros / 1_000_000.0), bold = true)
                        Text(
                            "Since $usageSince. Estimate only: web searches, pictures and MLS lookups " +
                                "cost a bit extra. Check platform.openai.com for your real bill.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            vm.prefs.resetUsage()
                            usageIn = 0; usageOut = 0; usageCalls = 0; usageCostMicros = 0
                            usageSince = vm.prefs.usageSince
                        }) { Text("Reset counter") }
                    }
                }
                Text("MLS (Flexmls)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = mlsUrl, onValueChange = { mlsUrl = it },
                    label = { Text("MLS server address") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.mlsConnected) "Connected ✓" else "Not connected",
                        Modifier.weight(1f),
                        color = if (vm.mlsConnected) Color(0xFF2F7FD1) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (vm.mlsConnected) {
                        OutlinedButton(onClick = { vm.disconnectMls() }, shape = RoundedCornerShape(20.dp)) {
                            Text("Disconnect")
                        }
                    } else {
                        Button(
                            onClick = {
                                vm.prefs.mlsUrl = mlsUrl
                                vm.connectMls { url ->
                                    try {
                                        settingsContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } catch (_: ActivityNotFoundException) {
                                        vm.showError("No web browser found to open the Flexmls sign-in.")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                        ) { Text("Connect MLS") }
                    }
                }
                if (vm.mlsStatus.isNotBlank()) {
                    Text(vm.mlsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Voice input language", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Phone default", "en-US" to "English", "zh-CN" to "中文").forEach { (tag, label) ->
                        FilterChip(
                            selected = voiceLang == tag,
                            onClick = { voiceLang = tag },
                            label = { Text(label) },
                        )
                    }
                }
                Text("Speaking voice", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Voices installed on your phone. Tap one to hear it. Pitch up sounds younger, down sounds deeper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !voiceTabZh, onClick = { voiceTabZh = false }, label = { Text("English voice") })
                    FilterChip(selected = voiceTabZh, onClick = { voiceTabZh = true }, label = { Text("中文 voice") })
                }
                val shownVoices = voiceOptions.filter { it.chinese == voiceTabZh }
                val chosenVoice = if (voiceTabZh) voiceZh else voiceEn
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = chosenVoice.isBlank(),
                            onClick = {
                                if (voiceTabZh) voiceZh = "" else voiceEn = ""
                                vm.previewVoice("", voiceTabZh, pitch, rate)
                            },
                            label = { Text("Phone default") },
                        )
                    }
                    items(shownVoices) { v ->
                        FilterChip(
                            selected = chosenVoice == v.name,
                            onClick = {
                                if (voiceTabZh) voiceZh = v.name else voiceEn = v.name
                                vm.previewVoice(v.name, voiceTabZh, pitch, rate)
                            },
                            label = { Text(v.label) },
                        )
                    }
                }
                if (voiceOptions.isEmpty()) {
                    Text(
                        "Voice list not ready yet; close and reopen Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Pitch: %.1f".format(pitch), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2.0f,
                    onValueChangeFinished = { vm.previewVoice(chosenVoice, voiceTabZh, pitch, rate) },
                )
                Text("Speed: %.1f".format(rate), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2.0f,
                    onValueChangeFinished = { vm.previewVoice(chosenVoice, voiceTabZh, pitch, rate) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Show model and token details under answers",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = showDetails, onCheckedChange = { showDetails = it })
                }
                Text("Background", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(Backgrounds) { b ->
                        val selected = bgChoice == b.key
                        Column(
                            Modifier.width(76.dp).clickable { bgChoice = b.key },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(width = 64.dp, height = 112.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) Color(0xFF2F7FD1) else Color(0xFFCFE3F7),
                                        RoundedCornerShape(14.dp),
                                    ),
                            ) {
                                AppBackground(b.key, startPage = true)
                            }
                            Text(
                                b.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color(0xFF2F7FD1) else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Text("Extra notes about me", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your main profile is the about_me.md file in the project. Add anything extra here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = aboutMe, onValueChange = { aboutMe = it },
                    minLines = 3, maxLines = 8,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Memory", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Facts the kitty saves when you say \"remember…\", or add your own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newMemory, onValueChange = { newMemory = it },
                        placeholder = { Text("e.g. My mom likes tea") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { memories = memories + newMemory.trim(); newMemory = "" },
                        enabled = newMemory.isNotBlank(),
                    ) { Text("Add") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Learn new facts from chats automatically",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = learnFacts, onCheckedChange = { learnFacts = it })
                }
                if (memories.isEmpty()) {
                    Text("Nothing saved yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                memories.forEachIndexed { i, fact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fact, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { memories = memories.filterIndexed { j, _ -> j != i } }) {
                            Text("Forget")
                        }
                    }
                }
                Text("Recent activity", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Short notes the kitty writes about your recent chats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recent.isEmpty()) {
                    Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                recent.reversed().forEach { entry ->
                    val parts = entry.split("|", limit = 3)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (parts.size == 3) "${parts[1]} · ${parts[2]}" else entry,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { recent = recent - entry }) { Text("Forget") }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.prefs.apiKey = key
                vm.prefs.model = model
                vm.prefs.agentName = name
                vm.prefs.voiceLanguage = voiceLang
                vm.prefs.showDetails = showDetails
                vm.prefs.background = bgChoice
                vm.prefs.learnFacts = learnFacts
                vm.prefs.memories = memories
                vm.prefs.aboutMe = aboutMe
                vm.prefs.recentActivity = recent
                vm.prefs.mlsUrl = mlsUrl
                vm.prefs.voiceEn = voiceEn
                vm.prefs.voiceZh = voiceZh
                vm.prefs.ttsPitch = pitch
                vm.prefs.ttsRate = rate
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun UsageLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = Color(0xFF1E4F80),
        )
    }
}
