package me.reply.app
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.reply.app.data.ApiKeyRepository
import me.reply.app.data.AddKeyResult
import me.reply.app.data.ContactProfile
import me.reply.app.data.ContactProfileRepository
import me.reply.app.data.KeyStatus
import me.reply.app.data.LanguageType
import me.reply.app.data.LengthType
import me.reply.app.data.RelationType
import me.reply.app.data.ToneType
import me.reply.app.ui.theme.SmartReplyTheme
import me.reply.app.uis.ApiKeysViewModel
import me.reply.app.uis.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var apiKeyRepository: ApiKeyRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d("MainActivity", if (isGranted) "Notification permission granted." else "Notification permission denied.")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        setContent {
            SmartReplyTheme {
                AppNavigator(apiKeyRepository)
            }
        }
    }
}
@Composable
fun AppNavigator(apiKeyRepository: ApiKeyRepository) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val hasNotificationPermission = isNotificationServiceEnabled(context)
    val hasApiKey = apiKeyRepository.hasAnyActiveKey()

    val startDestination = when {
        !hasNotificationPermission -> "permission"
        !hasApiKey                 -> "api_keys"
        else                       -> "main"
    }

    NavHost(
        navController    = navController,
        startDestination = startDestination,
        enterTransition  = { slideInHorizontally(initialOffsetX = { it }) + fadeIn() },
        exitTransition   = { slideOutHorizontally(targetOffsetX = { -it }) + fadeOut() },
        popEnterTransition  = { slideInHorizontally(initialOffsetX = { -it }) + fadeIn() },
        popExitTransition   = { slideOutHorizontally(targetOffsetX = { it }) + fadeOut() }
    ) {
        composable("permission") {
            PermissionScreen(
                onPermissionGranted = {
                    navController.navigate("api_keys") { popUpTo("permission") { inclusive = true } }
                }
            )
        }
        composable("api_keys") {
            val hasPrevious = navController.previousBackStackEntry != null
            ApiKeysScreen(
                onNavigateBack = {
                    if (apiKeyRepository.hasAnyActiveKey()) {
                        if (hasPrevious) {
                            // Came from main via ⚙ — just go back
                            navController.popBackStack()
                        } else {
                            // First-run setup — navigate forward to main
                            navController.navigate("main") {
                                popUpTo("api_keys") { inclusive = true }
                            }
                        }
                    }
                },
                showBackButton = hasPrevious
            )
        }
        composable("main") {
            MainAppScreen(
                onContactClick  = { contactName -> navController.navigate("chat/$contactName") },
                onSettingsClick = { navController.navigate("api_keys") }
            )
        }
        composable("chat/{contactName}") { backStackEntry ->
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Unknown"
            ChatHistoryScreen(
                contactName     = contactName,
                onNavigateBack  = { navController.popBackStack() }
            )
        }
    }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledListeners.any { it == context.packageName }
}
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    context.startActivity(intent)
}

// ---------------------------------------------------------------------------
// API Keys Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    viewModel: ApiKeysViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    showBackButton: Boolean
) {
    val keys by viewModel.keys.collectAsState()
    val activeKeyId by viewModel.activeKeyId.collectAsState()

    var newKeyText   by remember { mutableStateOf("") }
    var newKeyLabel  by remember { mutableStateOf("") }
    var addError     by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }  // key id
    val focusManager = LocalFocusManager.current

    // Delete confirmation dialog
    if (deleteTarget != null) {
        val isLastKey = keys.size == 1
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete API Key") },
            text  = {
                Text(
                    if (isLastKey)
                        "This is your only key. Smart replies will stop working until you add a new one. Delete anyway?"
                    else
                        "This key will be permanently removed. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteKey(deleteTarget!!)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showBackButton) "API Keys" else "Add Your API Key") },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // Help guide button — always visible
                    var showGuideFromKeys by remember { mutableStateOf(false) }
                    if (showGuideFromKeys) {
                        GuideBottomSheet(onDismiss = { showGuideFromKeys = false })
                    }
                    IconButton(onClick = { showGuideFromKeys = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Help guide",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // First-run mode: show Continue button once a key is added
                    if (!showBackButton && keys.isNotEmpty()) {
                        TextButton(onClick = onNavigateBack) {
                            Text("Continue →", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Existing keys
            items(keys) { apiKey ->
                val isActive = apiKey.id == activeKeyId
                val statusColor = when (apiKey.status) {
                    KeyStatus.ACTIVE       -> Color(0xFF4CAF50)
                    KeyStatus.RATE_LIMITED -> Color(0xFFFFC107)
                    KeyStatus.INVALID      -> MaterialTheme.colorScheme.error
                    KeyStatus.DISABLED     -> Color.Gray
                }
                val statusLabel = when (apiKey.status) {
                    KeyStatus.ACTIVE       -> "Active"
                    KeyStatus.RATE_LIMITED -> "Rate Limited"
                    KeyStatus.INVALID      -> "Invalid"
                    KeyStatus.DISABLED     -> "Disabled"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = apiKey.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = viewModel.maskedKey(apiKey.key),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Status badge
                            Surface(
                                color  = statusColor.copy(alpha = 0.15f),
                                shape  = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text     = statusLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = statusColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            // Delete button
                            IconButton(onClick = { deleteTarget = apiKey.id }) {
                                Icon(
                                    imageVector        = Icons.Default.Delete,
                                    contentDescription = "Delete key",
                                    tint               = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        // "Set Active" row — only shown if not already active
                        if (!isActive && apiKey.status == KeyStatus.ACTIVE) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick  = { viewModel.setActiveKey(apiKey.id) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Set as Active")
                            }
                        }
                    }
                }
            }

            // Cap warning
            if (keys.size >= 5) {
                item {
                    Text(
                        text  = "Maximum 5 keys reached. Delete one to add another.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Add-new-key section
            if (keys.size < 5) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text  = "Add New API Key",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = newKeyText,
                        onValueChange = { newKeyText = it; addError = null },
                        label         = { Text("Gemini API Key") },
                        placeholder   = { Text("AIzaSy...") },
                        singleLine    = true,
                        isError       = addError != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addError != null) {
                        Text(
                            text  = addError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = newKeyLabel,
                        onValueChange = { newKeyLabel = it },
                        label         = { Text("Nickname (optional)") },
                        placeholder   = { Text("e.g. Account 1") },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val isValidFormat = newKeyText.startsWith("AIzaSy") || newKeyText.startsWith("AQ.")
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            when (viewModel.addKey(newKeyText.trim(), newKeyLabel.trim())) {
                                AddKeyResult.Added    -> { newKeyText = ""; newKeyLabel = ""; addError = null }
                                AddKeyResult.Duplicate -> addError = "This key is already added."
                                AddKeyResult.CapReached -> addError = "Maximum 5 keys allowed."
                            }
                        },
                        enabled  = newKeyText.isNotBlank() && isValidFormat,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Key")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(onPermissionGranted: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (isNotificationServiceEnabled(context)) {
            onPermissionGranted()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "To provide smart suggestions, this app needs permission to read your notifications.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { openNotificationSettings(context) }) {
            Text(text = "Grant Permission in Settings")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onContactClick: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    val indexingProgress by viewModel.indexingProgress.collectAsState()
    val isImporting = indexingProgress != null
    var contactToDelete by remember { mutableStateOf<String?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                val uriMap = uris.associateWith { getFileName(context, it) ?: "unknown.txt" }
                viewModel.processAndIndexFiles(uriMap, context)
            }
        }
    )

    if (contactToDelete != null) {
        DeleteConfirmationDialog(
            contactName = contactToDelete!!,
            onConfirm = {
                viewModel.deleteContact(contactToDelete!!)
                contactToDelete = null
            },
            onDismiss = {
                contactToDelete = null
            }
        )
    }

    var showGuide by remember { mutableStateOf(false) }
    if (showGuide) {
        GuideBottomSheet(onDismiss = { showGuide = false })
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Imported Chats", style = MaterialTheme.typography.headlineSmall)
            Row {
                IconButton(onClick = { showGuide = true }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Help guide",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Manage API Keys"
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts) { contactName ->
                ContactListRow(
                    contactName     = contactName,
                    profileRepo     = viewModel.contactProfileRepository,
                    onClick         = { onContactClick(contactName) },
                    onLongClick     = { contactToDelete = contactName }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Import progress indicator — visible only while indexing is running
        if (isImporting) {
            val (current, total) = indexingProgress!!
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = current.toFloat() / total.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Indexing chunk $current of $total...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { filePickerLauncher.launch(arrayOf("text/plain")) },
            enabled = !isImporting
        ) {
            Text(text = if (isImporting) "Importing…" else "Import New Chat(s)")
        }
    }
}

// ---------------------------------------------------------------------------
// Contact List Row  (tap = open chat, ⚙ = profile sheet, toggle = on/off)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactListRow(
    contactName: String,
    profileRepo: ContactProfileRepository,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var profile by remember(contactName) {
        mutableStateOf(profileRepo.getProfile(contactName))
    }
    var showSheet by remember { mutableStateOf(false) }

    if (showSheet) {
        ContactProfileBottomSheet(
            contactName = contactName,
            initial     = profile,
            onSave      = { updated ->
                profileRepo.saveProfile(updated)
                profile  = updated
                showSheet = false
            },
            onDismiss   = { showSheet = false }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Contact name — takes all remaining space
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = contactName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (profile.isEnabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                if (profile.context.isNotBlank()) {
                    Text(
                        text  = profile.context.split(" ").take(5).joinToString(" ") + "...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }

            // Profile edit button
            IconButton(onClick = { showSheet = true }) {
                Icon(
                    imageVector        = Icons.Default.Edit,
                    contentDescription = "Edit reply profile",
                    tint               = MaterialTheme.colorScheme.primary
                )
            }

            // Enable / disable toggle
            Switch(
                checked         = profile.isEnabled,
                onCheckedChange = { enabled ->
                    profileRepo.setEnabled(contactName, enabled)
                    profile = profile.copy(isEnabled = enabled)
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Contact Profile Bottom Sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileBottomSheet(
    contactName: String,
    initial: ContactProfile,
    onSave: (ContactProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var tone       by remember { mutableStateOf(initial.tone) }
    var customTone by remember { mutableStateOf(initial.customTone) }
    var relation   by remember { mutableStateOf(initial.relation) }
    var language   by remember { mutableStateOf(initial.language) }
    var length     by remember { mutableStateOf(initial.length) }
    var context    by remember { mutableStateOf(initial.context) }
    val focusManager = LocalFocusManager.current

    val wordCount = context.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text  = "$contactName — Reply Profile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(20.dp))

            // ── TONE ────────────────────────────────────────────────────────
            SectionLabel("🎭 Tone")
            Spacer(modifier = Modifier.height(6.dp))
            ChipGroup {
                ToneType.values().forEach { t ->
                    FilterChip(
                        selected = tone == t,
                        onClick  = { tone = t },
                        label    = { Text(t.label()) }
                    )
                }
            }
            if (tone == ToneType.CUSTOM) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value         = customTone,
                    onValueChange = { if (it.length <= 30) customTone = it },
                    label         = { Text("Describe tone (e.g. sarcastic, brief)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── RELATION ────────────────────────────────────────────────────
            SectionLabel("💬 Relation")
            Spacer(modifier = Modifier.height(6.dp))
            ChipGroup {
                RelationType.values().forEach { r ->
                    FilterChip(
                        selected = relation == r,
                        onClick  = { relation = r },
                        label    = { Text(r.label()) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── LANGUAGE ────────────────────────────────────────────────────
            SectionLabel("🌐 Language Style")
            Spacer(modifier = Modifier.height(6.dp))
            ChipGroup {
                LanguageType.values().forEach { l ->
                    FilterChip(
                        selected = language == l,
                        onClick  = { language = l },
                        label    = { Text(l.label()) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── REPLY LENGTH ────────────────────────────────────────────────
            SectionLabel("📏 Reply Length")
            Spacer(modifier = Modifier.height(6.dp))
            ChipGroup {
                LengthType.values().forEach { l ->
                    FilterChip(
                        selected = length == l,
                        onClick  = { length = l },
                        label    = { Text(l.label()) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ── CONTEXT NOTE ────────────────────────────────────────────────
            SectionLabel("📝 Context Note")
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value         = context,
                onValueChange = { new ->
                    val words = new.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (words.size <= 40) context = new
                },
                placeholder   = { Text("e.g. close friend, we joke around and sometimes cuss") },
                minLines      = 3,
                maxLines      = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier      = Modifier.fillMaxWidth()
            )
            Text(
                text  = "$wordCount / 40 words",
                style = MaterialTheme.typography.labelSmall,
                color = if (wordCount >= 38) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ── ACTIONS ─────────────────────────────────────────────────────
            Row(
                modifier             = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick  = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSave(
                            ContactProfile(
                                contactName = contactName,
                                isEnabled   = initial.isEnabled,
                                tone        = tone,
                                customTone  = customTone,
                                relation    = relation,
                                language    = language,
                                length      = length,
                                context     = context.trim()
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save ✓") }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
    HorizontalDivider(modifier = Modifier.padding(top = 4.dp), thickness = 0.5.dp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(4.dp)
    ) {
        content()
    }
}

// Enum display labels
fun ToneType.label()     = when (this) { ToneType.CASUAL -> "Casual"; ToneType.FORMAL -> "Formal"; ToneType.NATURAL -> "Natural"; ToneType.CUSTOM -> "✏ Custom" }
fun RelationType.label() = when (this) { RelationType.FRIEND -> "👫 Friend"; RelationType.FAMILY -> "👨\u200d👩\u200d👧 Family"; RelationType.COLLEAGUE -> "💼 Work"; RelationType.PARTNER -> "❤️ Partner"; RelationType.ACQUAINTANCE -> "🤝 Acquaintance" }
fun LanguageType.label() = when (this) { LanguageType.STANDARD -> "Standard"; LanguageType.HINGLISH -> "Hinglish"; LanguageType.SLANG_OK -> "Slang OK"; LanguageType.EMOJI_HEAVY -> "Emoji Heavy" }
fun LengthType.label()   = when (this) { LengthType.SHORT -> "Short"; LengthType.MEDIUM -> "Medium"; LengthType.DETAILED -> "Detailed" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    contactName: String,
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(key1 = contactName) {
        viewModel.loadChatHistory(contactName)
    }
    DisposableEffect(key1 = Unit) {
        onDispose {
            viewModel.clearChatHistory()
        }
    }
    val chatHistory by viewModel.selectedChatHistory.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(contactName) }, navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        }) }
    ) { paddingValues ->
        val listState = rememberLazyListState()

        // Jump to newest message as soon as history is loaded
        LaunchedEffect(chatHistory.isNotEmpty()) {
            if (chatHistory.isNotEmpty()) {
                listState.scrollToItem(index = chatHistory.size - 1)
            }
        }

        LazyColumn(
            state    = listState,
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)
        ) {
            items(chatHistory) { message ->
                val alignment = if (message.isSentByMe) Alignment.CenterEnd else Alignment.CenterStart
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
                    Text(
                        text = message.messageText,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .background(
                                color = if (message.isSentByMe) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (message.isSentByMe) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Guide Bottom Sheet
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideBottomSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            Text(
                text = "📖 How to Use Reply.me",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Follow these steps to get smart AI replies on your WhatsApp notifications.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // ── STEP 1 ─────────────────────────────────────────────────────
            GuideStep(
                number = "1",
                emoji  = "💬",
                title  = "Export your WhatsApp chat",
                steps  = listOf(
                    "Open WhatsApp → go to the chat you want to use",
                    "Tap the ⋮ menu (top right) → More → Export chat",
                    "Select 'Without Media'",
                    "Save or share the .txt file to your phone's local storage (Downloads folder works fine)"
                ),
                note = "Do this for each contact you want smart replies for."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── STEP 2 ─────────────────────────────────────────────────────
            GuideStep(
                number = "2",
                emoji  = "📂",
                title  = "Import the chat into Reply.me",
                steps  = listOf(
                    "On the home screen, tap 'Import New Chat(s)'",
                    "A file picker opens — navigate to your Downloads folder",
                    "Select the exported .txt file (you can select multiple at once)",
                    "Tap Open — the app starts reading and indexing the chat"
                ),
                note = "You can import multiple contacts at once by selecting multiple .txt files."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── STEP 3 ─────────────────────────────────────────────────────
            GuideStep(
                number = "3",
                emoji  = "⏳",
                title  = "What is the indexing progress bar?",
                steps  = listOf(
                    "After importing, the app processes each message through AI",
                    "This creates 'embeddings' — AI memory of your chat patterns",
                    "The progress bar shows how many chunks have been processed",
                    "Don't close the app while it's running — it'll pause"
                ),
                note = "Indexing makes replies more personal and context-aware. A large chat (10,000+ msgs) may take a few minutes."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── STEP 4 ─────────────────────────────────────────────────────
            GuideStep(
                number = "4",
                emoji  = "🔑",
                title  = "Generate a Gemini API Key",
                steps  = listOf(
                    "Open your browser and go to: aistudio.google.com",
                    "Sign in with your Google account",
                    "Click 'Get API Key' → 'Create API key in new project'",
                    "Copy the generated key (starts with 'AIza...')",
                    "Paste it in the API Keys screen in this app"
                ),
                note = "The free tier gives you 15 requests/minute and 1 million tokens/day — more than enough for personal use."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── STEP 5 ─────────────────────────────────────────────────────
            GuideStep(
                number = "5",
                emoji  = "🔄",
                title  = "Add multiple API keys",
                steps  = listOf(
                    "Tap ⚙ (top right) → add as many keys as you want (up to 5)",
                    "Each key can be from a different Google account",
                    "If one key hits its rate limit, the app automatically rotates to the next",
                    "Keys marked 'Rate Limited' auto-recover after 24 hours",
                    "Delete any key anytime using the 🗑 button"
                ),
                note = "Having 2–3 keys from different accounts ensures uninterrupted smart replies even during heavy use."
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── DONE ───────────────────────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("✅", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "You're all set!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "When WhatsApp messages arrive, Reply.me will show AI-generated quick reply buttons in the notification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick  = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Got it!")
            }
        }
    }
}

@Composable
private fun GuideStep(
    number: String,
    emoji:  String,
    title:  String,
    steps:  List<String>,
    note:   String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text     = number,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style    = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color    = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text  = "$emoji $title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Steps
            steps.forEachIndexed { i, step ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text  = "  •  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text  = step,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            // Note
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text     = "ℹ️  $note",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    contactName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Delete Chat History") },
        text = { Text(text = "Are you sure you want to delete all messages for '$contactName'?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@SuppressLint("Range")
private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/')
        if (cut != -1) {
            result = result?.substring(cut!! + 1)
        }
    }
    return result
}

