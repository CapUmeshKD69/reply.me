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
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.reply.app.data.UserSettingsRepository
import me.reply.app.ui.theme.SmartReplyTheme
import me.reply.app.uis.MainViewModel
import me.reply.app.uis.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var userSettings: UserSettingsRepository
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission granted.")
        } else {
            Log.d("MainActivity", "Notification permission denied.")
        }
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
                AppNavigator(userSettings)
            }
        }
    }
}
@Composable
fun AppNavigator(userSettings: UserSettingsRepository) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val hasNotificationPermission = isNotificationServiceEnabled(context)
    val hasApiKey = userSettings.hasApiKey()

    val startDestination = when {
        !hasNotificationPermission -> "permission"
        !hasApiKey -> "settings"
        else -> "main"
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("permission") {
            PermissionScreen(
                onPermissionGranted = {
                    navController.navigate("settings") { popUpTo("permission") { inclusive = true } }
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onApiKeySaved = {
                    navController.navigate("main") { popUpTo("settings") { inclusive = true } }
                }
            )
        }
        composable("main") {
            MainAppScreen(
                onContactClick = { contactName ->
                    navController.navigate("chat/$contactName")
                }
            )
        }
        composable("chat/{contactName}") { backStackEntry ->
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Unknown"
            ChatHistoryScreen(
                contactName = contactName,
                onNavigateBack = { navController.popBackStack() }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onApiKeySaved: () -> Unit
) {
    var apiKeyText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Scaffold(topBar = { TopAppBar(title = { Text("Initial Setup") }) }) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Please enter your Google AI API key to enable smart replies.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                label = { Text("Gemini API Key") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.saveApiKey(apiKeyText)
                    onApiKeySaved()
                },
                enabled = apiKeyText.isNotBlank() && (apiKeyText.startsWith("AIzaSy") || apiKeyText.startsWith("AQ."))
            ) {
                Text("Save and Continue")
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
    onContactClick: (String) -> Unit
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    var contactToDelete by remember { mutableStateOf<String?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                val uriMap = uris.associateWith { getFileName(context, it) ?: "unknown.txt" }
                viewModel.processAndIndexFiles(uriMap, context)
                Toast.makeText(context, "Import started in background...", Toast.LENGTH_SHORT).show()
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

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Imported Chats", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts) { contactName ->
                Box(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onContactClick(contactName) },
                        onLongClick = { contactToDelete = contactName }
                    ).padding(vertical = 12.dp)
                ) {
                    Text(text = contactName)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { filePickerLauncher.launch(arrayOf("text/plain")) }) {
            Text(text = "Import New Chat(s)")
        }
    }
}

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
        LazyColumn(
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

