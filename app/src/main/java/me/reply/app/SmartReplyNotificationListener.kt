package me.reply.app

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import me.reply.app.ai.AiMessage
import me.reply.app.ai.generateSmartReplies
import me.reply.app.ai.getEmbedding
import me.reply.app.data.Message
import me.reply.app.data.MessageRepository
import me.reply.app.data.UserSettingsRepository
import me.reply.app.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class SmartReplyNotificationListener : NotificationListenerService() {

    @Inject
    lateinit var repository: MessageRepository

    @Inject
    lateinit var notificationHelper: NotificationHelper
    @Inject
    lateinit var userSettings: UserSettingsRepository
    private var lastProcessedKey: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn == null || sbn.packageName != "com.whatsapp") return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""
        Log.d("NotificationListener", "DEBUG: Received raw text = '[$text]'")
        val currentKey = "$title|$text"
        if (currentKey == lastProcessedKey) {
            Log.d("NotificationListener", "Ignoring duplicate notification update.")
            return
        }
        lastProcessedKey = currentKey
        if (text.isBlank() || title.isBlank()) return
        val ignoredPhrases = listOf(
            "new messages",
            "missed call",
            "messages from",
            "Calling…",
            "Ringing…",
            "Ongoing voice call"
        )
        if (ignoredPhrases.any { text.contains(it, ignoreCase = true) }) {
            Log.d("NotificationListener", "Ignoring system message: '$text'")
            return
        }


        serviceScope.launch {
            val isImported = repository.isContactImported(title)
            if (isImported) {
                Log.d("NotificationListener", "SUCCESS: Valid message from '$title'. Triggering AI engine.")
                handleNewNotification(contactName = title, newMessageText = text, originalNotification = sbn.notification)
            } else {
                Log.d("NotificationListener", "Ignoring notification from unknown contact: '$title'")
            }
        }
    }

    private suspend fun handleNewNotification(contactName: String, newMessageText: String, originalNotification: android.app.Notification) {
        try {
            Log.d("AI_ENGINE", "Attempting to get embedding for text: '$newMessageText'")
            val historyFromDb = repository.getAllMessagesForContact(contactName)
            val apiKey = userSettings.getApiKey()
            if (apiKey == null) {
                Log.e("AI_ENGINE", "API Key is missing. Cannot generate replies.")
                return
            }
            if (historyFromDb.isEmpty()) {
                Log.w("AI_ENGINE", "No chat history found for $contactName. Cannot generate replies.")
                showFallbackNotification(contactName, newMessageText, originalNotification)
                return
            }

            val ourUserName = detectOurUserName(contactName, historyFromDb)
            Log.d("AI_ENGINE", "Using user identity: '$ourUserName' for contact: '$contactName'")

            val embeddingVector = getEmbedding(newMessageText,apiKey)
            if (embeddingVector == null) {
                Log.e("AI_ENGINE", "Could not get embedding for new message.")
                showFallbackNotification(contactName, newMessageText, originalNotification)
                return
            }

            val newMessage = Message(
                contactName = contactName,
                messageText = newMessageText,
                isSentByMe = false, // It's from them
                embeddingJson = Json.encodeToString(embeddingVector)
            )



            Log.d("NotificationListener", "New message from $contactName saved to database.")


            val history = repository.getAllMessagesForContact(contactName)
            val insertedMessages = repository.insertAndGetMessages(listOf(newMessage))
            if (insertedMessages.isEmpty()) {
                Log.e("AI_ENGINE", "Failed to save new message to database.")
                showFallbackNotification(contactName, newMessageText, originalNotification)
                return
            }
            val historyForAI = history.map { dbMsg ->
                AiMessage(

                    sender = if (dbMsg.isSentByMe) ourUserName else contactName,
                    content = dbMsg.messageText
                )
            }

            val indexedHistoryForAI = history.associate { dbMsg ->
                val vector = try {
                    Json.decodeFromString<List<Float>>(dbMsg.embeddingJson)
                } catch (e: Exception) {
                    emptyList()
                }
                val aiMessageKey = AiMessage(

                    sender = if (dbMsg.isSentByMe) ourUserName else contactName,
                    content = dbMsg.messageText
                )
                aiMessageKey to vector
            }

            val newAiMessage = AiMessage(
                sender = contactName,
                content = newMessageText
            )
            val smartReplies = generateSmartReplies(
                newMessage = newAiMessage,
                history = historyForAI,
                indexedHistory = indexedHistoryForAI,
                ourUser = ourUserName
            )

            Log.d("AI_ENGINE", "Generated Replies: $smartReplies")

            notificationHelper.showSmartReplyNotification(
                contactName = contactName,
                messageText = newMessageText,
                replies = smartReplies,
                originalNotification = originalNotification,
                ourUserName = ourUserName
            )
        } catch (e: Exception) {
            Log.e("NotificationListener", "Error in handleNewNotification", e)
            showFallbackNotification(contactName, newMessageText, originalNotification)
        }
    }

    private suspend fun detectOurUserName(contactName: String, history: List<Message>): String {

        val storedUserName = getUserNameFromStorage(contactName)
        if (storedUserName != "Me") {
            return storedUserName
        }

        val myMessages = history.filter { it.isSentByMe }
        if (myMessages.isNotEmpty()) {
            val potentialNames = mutableSetOf<String>()

            myMessages.forEach { message ->
                val namePatterns = listOf(
                    Regex("""(?:I am|I'm|my name is|this is)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""", RegexOption.IGNORE_CASE),
                    Regex("""-\s*([A-Z][a-z]+)\s*:""")
                )

                namePatterns.forEach { pattern ->
                    val match = pattern.find(message.messageText)
                    match?.groups?.get(1)?.value?.let { potentialName ->
                        if (!potentialName.equals(contactName, ignoreCase = true)) {
                            potentialNames.add(potentialName.trim())
                        }
                    }
                }
            }

            if (potentialNames.isNotEmpty()) {
                val detectedName = potentialNames.first()
                storeUserName(contactName, detectedName)
                return detectedName
            }
        }

        return "Me"
    }


    private fun storeUserName(contactName: String, userName: String) {
        val prefs = getSharedPreferences("user_mapping", Context.MODE_PRIVATE)
        prefs.edit().putString(contactName, userName).apply()
    }

    private fun getUserNameFromStorage(contactName: String): String {
        return try {
            val prefs = getSharedPreferences("user_mapping", Context.MODE_PRIVATE)
            prefs.getString(contactName, "Me") ?: "Me"
        } catch (e: Exception) {
            "Me"
        }
    }

    private suspend fun showFallbackNotification(contactName: String, messageText: String, originalNotification: android.app.Notification) {
        val fallbackReplies = listOf("Okay 👍", "Sounds good!", "Let me check")
        val historyFromDb = repository.getAllMessagesForContact(contactName)
        val ourUserName = detectOurUserName(contactName, historyFromDb)
        notificationHelper.showSmartReplyNotification(
            contactName = contactName,
            messageText = messageText,
            replies = fallbackReplies,
            originalNotification = originalNotification,
            ourUserName = ourUserName
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
