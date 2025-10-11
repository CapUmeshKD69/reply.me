package me.reply.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import me.reply.app.ui.theme.SmartReplyTheme
import me.reply.app.uis.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.compose.rememberLauncherForActivityResult
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Notification permission granted.")
            } else {
                Log.d("MainActivity", "Notification permission denied.")
            }
        }

    private fun askNotificationPermission() {
        // This is only necessary for Android version 13 or above
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
                AppNavigator()
            }
        }
    }
}


@Composable
fun AppNavigator() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val startDestination = if (isNotificationServiceEnabled(context)) "main" else "permission"

    NavHost(navController = navController, startDestination = startDestination) {
        // The Permission Screen
        composable("permission") {
            PermissionScreen(
                onGrantPermissionClick = { openNotificationSettings(context) }
            )
        }

        // The Main App Screen (list of contacts)
        composable("main") {
            MainAppScreen(
                onContactClick = { contactName ->
                    navController.navigate("chat/$contactName")
                }
            )
        }

        // The Chat History Screen
        composable("chat/{contactName}") { backStackEntry ->
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Unknown"
            ChatHistoryScreen(
                contactName = contactName,
                onNavigateBack = {
                    navController.popBackStack()
                }
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

@Composable
fun PermissionScreen(onGrantPermissionClick: () -> Unit) {
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
        Button(onClick = onGrantPermissionClick) {
            Text(text = "Grant Permission")
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
                val uriMap = mutableMapOf<Uri, String>()
                uris.forEach { uri ->
                    val fileName = getFileName(context, uri)
                    if (fileName != null) {
                        uriMap[uri] = fileName
                    }
                }
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
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Imported Chats", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        // The scrollable list that displays the contact names.
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts) { contactName ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onContactClick(contactName) },
                            onLongClick = { contactToDelete = contactName }
                        )
                        .padding(vertical = 12.dp)
                ) {
                    Text(text = contactName)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { filePickerLauncher.launch(arrayOf("text/plain", "application/txt")) }) {
            Text(text = "Import New Chat(s)")
        }
    }
}

// The new screen for displaying a single contact's chat history
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
        topBar = {
            TopAppBar(
                title = { Text(contactName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
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
        text = { Text(text = "Are you sure you want to delete all messages for '$contactName'? This action cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
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
