package me.reply.app

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import me.reply.app.ai.AiCallResult
import me.reply.app.ai.AiMessage
import me.reply.app.ai.generateSmartReplies
import me.reply.app.ai.getAiChatResponseFallback
import me.reply.app.ai.getEmbedding
import me.reply.app.data.ApiKeyRepository
import me.reply.app.data.ContactProfileRepository
import me.reply.app.data.Message
import me.reply.app.data.MessageRepository
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

private val HARDCODED_FALLBACK = listOf("Okay 👍", "Sounds good!")

@AndroidEntryPoint
class SmartReplyNotificationListener : NotificationListenerService() {
    @Inject lateinit var repository: MessageRepository
    @Inject lateinit var notificationHelper: NotificationHelper
    @Inject lateinit var apiKeyRepository: ApiKeyRepository
    @Inject lateinit var contactProfileRepository: ContactProfileRepository

    private var lastProcessedKey: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.packageName != "com.whatsapp") return
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val textObj = extras.get("android.text")
        val text = when (textObj) {
            is String               -> textObj
            is android.text.Spanned -> textObj.toString()
            else                    -> ""
        }
        Log.d("NotificationListener", "DEBUG: Received raw text = '[$text]'")
        val currentKey = "$title|$text"
        if (currentKey == lastProcessedKey) {
            Log.d("NotificationListener", "Ignoring duplicate notification update.")
            return
        }
        lastProcessedKey = currentKey
        if (text.isBlank() || title.isBlank()) return
        val ignoredPhrases = listOf(
            "new messages", "missed call", "messages from",
            "Calling…", "Ringing…", "Ongoing voice call"
        )
        if (ignoredPhrases.any { text.contains(it, ignoreCase = true) }) {
            Log.d("NotificationListener", "Ignoring system message: '$text'")
            return
        }
        serviceScope.launch {
            val isImported = repository.isContactImported(title)
            if (isImported) {
                if (!contactProfileRepository.isEnabled(title)) {
                    Log.d("NotificationListener", "Smart replies disabled for '$title'. Skipping.")
                    return@launch
                }
                Log.d("NotificationListener", "Valid message from '$title'. Triggering AI engine.")
                handleNewNotification(contactName = title, newMessageText = text, originalNotification = sbn.notification)
            } else {
                Log.d("NotificationListener", "Ignoring notification from unknown contact: '$title'")
            }
        }
    }

    private suspend fun handleNewNotification(
        contactName: String,
        newMessageText: String,
        originalNotification: android.app.Notification
    ) {
        try {
            val historyFromDb = repository.getAllMessagesForContact(contactName)
            val apiKey = apiKeyRepository.getActiveKey()
            if (apiKey == null) {
                Log.e("AI_ENGINE", "No active API key. Cannot generate replies.")
                showFallbackNotification(contactName, newMessageText, originalNotification, null)
                return
            }
            if (historyFromDb.isEmpty()) {
                Log.w("AI_ENGINE", "No chat history for $contactName.")
                showFallbackNotification(contactName, newMessageText, originalNotification, apiKey)
                return
            }
            val ourUserName = detectOurUserName(contactName, historyFromDb)
            Log.d("AI_ENGINE", "User identity: '$ourUserName' for '$contactName'")

            val profileLine = contactProfileRepository.buildProfileLine(contactName)
            // Step 1 — Get embedding with one key-rotation attempt on fault
            val (embeddingVector, activeKey) = tryEmbeddingWithRotation(newMessageText, apiKey)
            if (embeddingVector == null) {
                Log.e("AI_ENGINE", "Could not get embedding for new message.")
                showFallbackNotification(contactName, newMessageText, originalNotification, activeKey)
                return
            }

            // Step 2 — Save new message to DB (embedding already computed)
            val newMessage = Message(
                contactName   = contactName,
                messageText   = newMessageText,
                isSentByMe    = false,
                embeddingJson = Json.encodeToString(embeddingVector)
            )
            val insertedMessages = repository.insertAndGetMessages(listOf(newMessage))
            if (insertedMessages.isEmpty()) {
                Log.e("AI_ENGINE", "Failed to save new message to database.")
                showFallbackNotification(contactName, newMessageText, originalNotification, activeKey)
                return
            }
            Log.d("NotificationListener", "New message from $contactName saved to database.")

            // Step 3 — Build AI context from pre-insert snapshot (new message passed separately)
            val historyForAI = historyFromDb.map { dbMsg ->
                AiMessage(
                    sender  = if (dbMsg.isSentByMe) ourUserName else contactName,
                    content = dbMsg.messageText
                )
            }
            val indexedHistoryForAI = historyFromDb.associate { dbMsg ->
                val vector = try {
                    Json.decodeFromString<List<Float>>(dbMsg.embeddingJson)
                } catch (e: Exception) { emptyList() }
                AiMessage(
                    sender  = if (dbMsg.isSentByMe) ourUserName else contactName,
                    content = dbMsg.messageText
                ) to vector
            }
            val newAiMessage = AiMessage(sender = contactName, content = newMessageText)

            // Step 4 — Generate replies with one key-rotation attempt on fault
            val smartReplies = tryChatWithRotation(
                newMessage         = newAiMessage,
                historyForAI       = historyForAI,
                indexedHistoryForAI = indexedHistoryForAI,
                apiKey             = activeKey,
                ourUserName        = ourUserName,
                embeddingVector    = embeddingVector,
                profileLine        = profileLine
            )
            Log.d("AI_ENGINE", "Generated Replies: $smartReplies")
            notificationHelper.showSmartReplyNotification(
                contactName          = contactName,
                messageText          = newMessageText,
                replies              = smartReplies,
                originalNotification = originalNotification,
                ourUserName          = ourUserName
            )
        } catch (e: Exception) {
            Log.e("NotificationListener", "Error in handleNewNotification", e)
            val key = apiKeyRepository.getActiveKey()
            showFallbackNotification(contactName, newMessageText, originalNotification, key)
        }
    }

    private fun tryEmbeddingWithRotation(text: String, initialKey: String): Pair<List<Float>?, String> {
        val (vec, errorCode) = getEmbedding(text, initialKey)
        if (vec != null) return Pair(vec, initialKey)

        if (errorCode != null) {
            apiKeyRepository.reportKeyError(initialKey, errorCode)
            val nextKey = apiKeyRepository.rotateToNextActiveKey() ?: return Pair(null, initialKey)
            val (retryVec, _) = getEmbedding(text, nextKey)
            return Pair(retryVec, nextKey)
        }
        return Pair(null, initialKey)
    }

    private fun tryChatWithRotation(
        newMessage: AiMessage,
        historyForAI: List<AiMessage>,
        indexedHistoryForAI: Map<AiMessage, List<Float>>,
        apiKey: String,
        ourUserName: String,
        embeddingVector: List<Float>,
        profileLine: String? = null
    ): List<String> {
        val result = generateSmartReplies(
            newMessage        = newMessage,
            history           = historyForAI,
            indexedHistory    = indexedHistoryForAI,
            apiKey            = apiKey,
            ourUser           = ourUserName,
            precomputedVector = embeddingVector,
            profileLine       = profileLine
        )
        return when (result) {
            is AiCallResult.Success -> result.replies.take(2)
            is AiCallResult.KeyFault -> {
                apiKeyRepository.reportKeyError(apiKey, result.httpCode)
                val nextKey = apiKeyRepository.rotateToNextActiveKey() ?: return HARDCODED_FALLBACK
                val retry = generateSmartReplies(
                    newMessage        = newMessage,
                    history           = historyForAI,
                    indexedHistory    = indexedHistoryForAI,
                    apiKey            = nextKey,
                    ourUser           = ourUserName,
                    precomputedVector = embeddingVector,
                    profileLine       = profileLine
                )
                if (retry is AiCallResult.Success) retry.replies.take(2) else HARDCODED_FALLBACK
            }
            is AiCallResult.OtherError -> HARDCODED_FALLBACK
        }
    }

    private suspend fun showFallbackNotification(
        contactName: String,
        messageText: String,
        originalNotification: android.app.Notification,
        apiKey: String?,
        profileLine: String? = null
    ) {
        val historyFromDb = repository.getAllMessagesForContact(contactName)
        val ourUserName   = detectOurUserName(contactName, historyFromDb)

        val replies = if (apiKey != null && historyFromDb.isNotEmpty()) {
            try {
                Log.d("AI_ENGINE", "Fallback: AI with recent history only.")
                val recentHistory = historyFromDb.takeLast(30).map { dbMsg ->
                    AiMessage(
                        sender  = if (dbMsg.isSentByMe) ourUserName else contactName,
                        content = dbMsg.messageText
                    )
                }
                val newAiMsg = AiMessage(sender = contactName, content = messageText)
                val result   = getAiChatResponseFallback(recentHistory, newAiMsg, ourUserName, apiKey, profileLine)
                when (result) {
                    is AiCallResult.Success -> result.replies.take(2).ifEmpty { HARDCODED_FALLBACK }
                    is AiCallResult.KeyFault -> {
                        apiKeyRepository.reportKeyError(apiKey, result.httpCode)
                        HARDCODED_FALLBACK
                    }
                    is AiCallResult.OtherError -> HARDCODED_FALLBACK
                }
            } catch (e: Exception) {
                Log.e("AI_ENGINE", "Fallback AI call failed: ${e.message}")
                HARDCODED_FALLBACK
            }
        } else {
            Log.w("AI_ENGINE", "Fallback: no key or history — using hardcoded replies.")
            HARDCODED_FALLBACK
        }

        notificationHelper.showSmartReplyNotification(
            contactName          = contactName,
            messageText          = messageText,
            replies              = replies,
            originalNotification = originalNotification,
            ourUserName          = ourUserName
        )
    }

    // ---- User identity detection ----

    private suspend fun detectOurUserName(contactName: String, history: List<Message>): String {
        val storedUserName = getUserNameFromStorage(contactName)
        if (storedUserName != "Me") return storedUserName
        val myMessages = history.filter { it.isSentByMe }
        if (myMessages.isNotEmpty()) {
            val potentialNames = mutableSetOf<String>()
            myMessages.forEach { message ->
                val namePatterns = listOf(
                    Regex("""(?:I am|I'm|my name is|this is)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""", RegexOption.IGNORE_CASE),
                    Regex("""-\s*([A-Z][a-z]+)\s*:""")
                )
                namePatterns.forEach { pattern ->
                    pattern.find(message.messageText)?.groups?.get(1)?.value?.let { name ->
                        if (!name.equals(contactName, ignoreCase = true)) potentialNames.add(name.trim())
                    }
                }
            }
            if (potentialNames.isNotEmpty()) {
                val detected = potentialNames.first()
                storeUserName(contactName, detected)
                return detected
            }
        }
        return "Me"
    }

    private fun storeUserName(contactName: String, userName: String) {
        getSharedPreferences("user_mapping", Context.MODE_PRIVATE)
            .edit().putString(contactName, userName).apply()
    }

    private fun getUserNameFromStorage(contactName: String): String {
        return try {
            getSharedPreferences("user_mapping", Context.MODE_PRIVATE)
                .getString(contactName, "Me") ?: "Me"
        } catch (e: Exception) { "Me" }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
