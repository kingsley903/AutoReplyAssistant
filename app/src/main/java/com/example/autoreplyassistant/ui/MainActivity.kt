package com.example.autoreplyassistant.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.autoreplyassistant.data.ActivityLogStore
import com.example.autoreplyassistant.data.AppSettings
import com.example.autoreplyassistant.data.ChatControlRepository
import com.example.autoreplyassistant.data.MessageQueueDatabase
import com.example.autoreplyassistant.data.MessageQueueRepository
import com.example.autoreplyassistant.data.PausedConversation
import com.example.autoreplyassistant.data.SettingsRepository
import com.example.autoreplyassistant.data.SupersededMessageTracker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(applicationContext)
        setContent {
            MaterialTheme {
                Dashboard(settingsRepository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun Dashboard(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = AppSettings())
    val logs by ActivityLogStore.items.collectAsState()

    var autoReply by remember(settings.autoReplyEnabled) { mutableStateOf(settings.autoReplyEnabled) }
    var autoPick by remember(settings.autoPickCallsEnabled) { mutableStateOf(settings.autoPickCallsEnabled) }
    var apiUrl by remember(settings.apiBaseUrl) { mutableStateOf(settings.apiBaseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.modelName) { mutableStateOf(settings.modelName) }
    var prompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var delayMs by remember(settings.replyDelayMs) { mutableStateOf(settings.replyDelayMs.toString()) }
    var ignored by remember(settings.ignoredSendersCsv) { mutableStateOf(settings.ignoredSendersCsv) }
    var ownerName by remember(settings.ownerName) { mutableStateOf(settings.ownerName) }
    var message by remember { mutableStateOf("") }

    // Chat control state
    val dbHelper = MessageQueueDatabase(context)
    val chatControl = remember { ChatControlRepository(dbHelper) }
    val messageRepository = remember { MessageQueueRepository(dbHelper) }
    val supersededTracker = remember { SupersededMessageTracker(messageRepository) }
    var globalPaused by remember(settings.globalPauseEnabled) { mutableStateOf(settings.globalPauseEnabled) }
    var pausedConversations by remember { mutableStateOf(chatControl.getAllPaused()) }
    var exceptionConversations by remember { mutableStateOf(chatControl.getAllExceptions()) }
    var pendingCount by remember { mutableStateOf(0) }
    var supersededCount by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        message = if (denied.isEmpty()) "Requested permissions granted." else "Permissions not granted: ${denied.joinToString()}"
    }

    fun openIntent(intent: Intent, fallbackMessage: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            message = fallbackMessage
        } catch (_: SecurityException) {
            message = fallbackMessage
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Auto Reply Assistant")
                    Text("WhatsApp messages • Standard phone calls", style = MaterialTheme.typography.labelSmall)
                }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Automation controls", style = MaterialTheme.typography.titleLarge)
                    SettingSwitch(
                        title = "WhatsApp Auto-Reply",
                        subtitle = "Replies to new WhatsApp and WhatsApp Business notifications.",
                        checked = autoReply,
                        onCheckedChange = { enabled ->
                            autoReply = enabled
                            scope.launch { repository.updateAutoReply(enabled) }
                        }
                    )
                    SettingSwitch(
                        title = "Auto-Pick Incoming Calls",
                        subtitle = "Answers standard cellular calls when this app is the default phone app.",
                        checked = autoPick,
                        onCheckedChange = { enabled ->
                            autoPick = enabled
                            scope.launch { repository.updateAutoPickCalls(enabled) }
                        }
                    )
                }
            }

            Text("Required Android access", style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    openIntent(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        "Notification listener settings are unavailable on this device."
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable Notification Access") }

            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val roleManager = context.getSystemService(RoleManager::class.java)
                        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                            openIntent(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), "Default dialer selection is unavailable.")
                        } else {
                            openIntent(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), "Default apps settings unavailable.")
                        }
                    } else {
                        val telecom = context.getSystemService(TelecomManager::class.java)
                        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                        if (telecom != null) openIntent(intent, "Default phone app selection is unavailable.")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Set as Default Phone App") }

            OutlinedButton(
                onClick = {
                    val permissions = buildList {
                        add(Manifest.permission.ANSWER_PHONE_CALLS)
                        add(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                    permissionLauncher.launch(permissions)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Request Runtime Permissions") }

            Text("AI reply configuration", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(apiUrl, { apiUrl = it }, label = { Text("API Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                apiKey, { apiKey = it }, label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(model, { model = it }, label = { Text("Model Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                prompt, { prompt = it }, label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth(), minLines = 3
            )
            OutlinedTextField(
                delayMs, { delayMs = it.filter(Char::isDigit).take(5) },
                label = { Text("Reply Delay (ms)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                ignored, { ignored = it },
                label = { Text("Ignored Contacts (comma-separated)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )
            OutlinedTextField(
                ownerName, { ownerName = it },
                label = { Text("Your WhatsApp Name (for outgoing detection)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Button(
                onClick = {
                    val parsedDelay = delayMs.toLongOrNull()
                    if (parsedDelay == null) {
                        message = "Reply delay must be a valid number."
                    } else if (apiUrl.isBlank() || model.isBlank()) {
                        message = "API Base URL and Model Name are required."
                    } else {
                        scope.launch {
                            repository.save(
                                AppSettings(
                                    autoReplyEnabled = autoReply,
                                    autoPickCallsEnabled = autoPick,
                                    apiBaseUrl = apiUrl,
                                    apiKey = apiKey,
                                    modelName = model,
                                    systemPrompt = prompt,
                                    replyDelayMs = parsedDelay.coerceIn(0L, 60_000L),
                                    ignoredSendersCsv = ignored,
                                    ownerName = ownerName
                                )
                            )
                            message = "Settings saved."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save Settings") }

            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent activity", style = MaterialTheme.typography.titleLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${logs.size} events", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(
                        onClick = { ActivityLogStore.clearLogs() }
                    ) {
                        Text("Clear Logs")
                    }
                }
            }
            Card {
                if (logs.isEmpty()) {
                    Text(
                        "No activity yet. Enable the required Android access and automation toggles.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(260.dp).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(logs) { item ->
                            Column {
                                Text("[${item.time}] ${item.status}", style = MaterialTheme.typography.labelLarge)
                                Text(item.details, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WhatsApp Control Commands", style = MaterialTheme.typography.titleLarge)
                    Text("Send these messages from your WhatsApp account to control automatic replies.", style = MaterialTheme.typography.bodySmall)

                    CommandRow(command = "/.this", description = "Silence automatic replies in this chat only.")
                    CommandRow(command = "/.all", description = "Silence automatic replies in every chat.")
                    CommandRow(command = "/.R.this", description = "Wake this chat (exception during global silence).")
                    CommandRow(command = "/.R.all", description = "Wake all chats (clear global and individual silence).")
                    CommandRow(command = "/.old", description = "Respond to a selected old message (reply to it first).")

                    Spacer(Modifier.height(8.dp))

                    Text("Current Status", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Global auto-reply:", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (globalPaused) "PAUSED" else "ACTIVE",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (globalPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Individually paused conversations:", style = MaterialTheme.typography.bodyMedium)
                        Text("${pausedConversations.size}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Global pause exceptions:", style = MaterialTheme.typography.bodyMedium)
                        Text("${exceptionConversations.size}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Pending messages:", style = MaterialTheme.typography.bodyMedium)
                        Text("${pendingCount}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Superseded (owner replied):", style = MaterialTheme.typography.bodyMedium)
                        Text("${supersededCount}", style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    SettingsRepository(context).updateGlobalPause(false)
                                    globalPaused = false
                                    message = "Global auto-reply resumed."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Resume Global") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    SettingsRepository(context).updateGlobalPause(true)
                                    globalPaused = true
                                    message = "Global auto-reply paused."
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Pause Global") }
                    }

                    if (pausedConversations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Individually Paused Conversations", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(pausedConversations) { conv ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(conv.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${conv.packageName} • ${chatControl.formatPausedDuration(conv.pausedAt)}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                chatControl.resume(conv.conversationKey)
                                                pausedConversations = chatControl.getAllPaused()
                                                message = "Resumed ${conv.displayName}."
                                            }
                                        }
                                    ) { Text("Resume") }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val count = chatControl.resumeAll()
                                    pausedConversations = chatControl.getAllPaused()
                                    message = "Resumed $count individually paused conversation(s)."
                                }
                            }
                        ) { Text("Resume All Individual Pauses") }
                    }

                    if (exceptionConversations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Global Pause Exceptions (allowed during global silence)", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(exceptionConversations) { conv ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(conv.displayName, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${conv.packageName} • ${chatControl.formatPausedDuration(conv.pausedAt)}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                chatControl.removeException(conv.conversationKey)
                                                exceptionConversations = chatControl.getAllExceptions()
                                                message = "Removed exception for ${conv.displayName}."
                                            }
                                        }
                                    ) { Text("Remove") }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    pendingCount = messageRepository.countByStatus("PENDING")
                                    supersededCount = supersededTracker.countSuperseded()
                                    pausedConversations = chatControl.getAllPaused()
                                    exceptionConversations = chatControl.getAllExceptions()
                                    message = "Status refreshed."
                                }
                            }
                        ) { Text("Refresh Status") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Important: WhatsApp calls are not handled. Automatic answering applies only to standard carrier calls, and requires this app to be selected as the default phone app. Notification access must be enabled manually in Android settings.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun CommandRow(command: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(command, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
