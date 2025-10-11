package me.reply.app.uis

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.reply.app.ai.AiMessage
import me.reply.app.ai.getEmbedding
import me.reply.app.ai.getEmbeddingsInBatch
import me.reply.app.ai.parseChatFile
import me.reply.app.data.Message
import me.reply.app.data.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MessageRepository
) : ViewModel() {

    val contacts: StateFlow<List<String>> = repository.getImportedContacts()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private val _selectedChatHistory = MutableStateFlow<List<Message>>(emptyList())
    val selectedChatHistory: StateFlow<List<Message>> = _selectedChatHistory.asStateFlow()

    private val _indexingProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val indexingProgress: StateFlow<Pair<Int, Int>?> = _indexingProgress.asStateFlow()

    fun loadChatHistory(contactName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _selectedChatHistory.value = repository.getAllMessagesForContact(contactName)
        }
    }

    fun clearChatHistory() {
        _selectedChatHistory.value = emptyList()
    }

    fun processAndIndexFiles(uriMap: Map<Uri, String>, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val embeddingApiKey = "AIzaSyDZo1RCOH-V7Nh_NDW-5qkdiBkDq6IRTsA"

            uriMap.forEach { (uri, fileName) ->
                try {
                    val fileContent = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }

                    if (fileContent != null) {
                        val parsedAiMessages = parseChatFile(fileContent)
                        if (parsedAiMessages.isEmpty()) return@forEach
                        val ignoredMessages = setOf(
                            "<Media omitted>",
                            "This message was deleted",
                            "You deleted this message",
                            ""
                            // Add any other specific phrases you want to ignore here
                        )
                        val filteredMessages = parsedAiMessages.filterNot {
                            it.content in ignoredMessages
                        }
                        if (filteredMessages.isEmpty()) {
                            Log.d("ViewModel", "File contained only ignored messages. Skipping.")
                            return@forEach
                        }

                        // set the limit for file size to save in dataset
                        val messageLimit = 2000

                        val finalMessages = if (filteredMessages.size > messageLimit) {
                            Log.d("ViewModel", "Chat file is large (${filteredMessages.size} messages). Taking the latest $messageLimit.")
                            filteredMessages.takeLast(messageLimit)
                        } else {
                            filteredMessages
                        }


                        val contactName = extractContactName(fileName)

                        val ourUserName = detectUserNameFromParsedMessages(finalMessages, contactName)
                        storeUserMapping(context, contactName, ourUserName)

                        val messagesToSave = finalMessages.map { aiMsg ->
                            Message(
                                contactName = contactName,
                                messageText = aiMsg.content,
                                isSentByMe = aiMsg.sender == ourUserName,
                                embeddingJson = ""
                            )
                        }

                        val savedMessages = repository.insertAndGetMessages(messagesToSave)
                        Log.d("ViewModel", "Parsed and saved ${savedMessages.size} messages for $contactName (User: $ourUserName)")

                        runIndexingProcess(savedMessages, embeddingApiKey)
                    }
                } catch (e: Exception) {
                    Log.e("ViewModel", "Error processing file $fileName: ${e.message}")
                }
            }
            _indexingProgress.value = null
            Log.d("ViewModel", "Finished processing all files.")
        }
    }

    private fun detectUserNameFromParsedMessages(messages: List<AiMessage>, contactName: String): String {
        if (messages.isEmpty()) return "Me"

        val senderStats = messages.groupingBy { it.sender }.eachCount()

        val potentialUsers = senderStats.filterKeys {
            !it.equals(contactName, ignoreCase = true) &&
                    !it.contains("WhatsApp", ignoreCase = true) &&
                    !it.contains("System", ignoreCase = true) &&
                    !it.contains("Group", ignoreCase = true)
        }

        if (potentialUsers.isEmpty()) return "Me"

        return potentialUsers.maxByOrNull { it.value }?.key ?: "Me"
    }

    private fun storeUserMapping(context: Context, contactName: String, ourUserName: String) {
        val prefs = context.getSharedPreferences("user_mapping", Context.MODE_PRIVATE)
        prefs.edit().putString(contactName, ourUserName).apply()
    }

    private suspend fun runIndexingProcess(messagesToIndex: List<Message>, apiKey: String) {
        Log.d("ViewModel", "Starting indexing process for ${messagesToIndex.size} messages...")
        val embeddingApiKey = "AIzaSyDZo1RCOH-V7Nh_NDW-5qkdiBkDq6IRTsA"
        val chunks = messagesToIndex.chunked(50)

        chunks.forEachIndexed { index, messageChunk ->
            _indexingProgress.value = (index + 1) to chunks.size

            val textsToEmbed = messageChunk.map { it.messageText }
            val embeddingsMap = getEmbeddingsInBatch(textsToEmbed,embeddingApiKey)

            if (embeddingsMap != null) {
                val updatedMessages = messageChunk.mapNotNull { message ->
                    embeddingsMap[message.messageText]?.let { vector ->
                        message.copy(embeddingJson = Json.encodeToString(vector))
                    }
                }
                if (updatedMessages.isNotEmpty()) {
                    repository.updateMessages(updatedMessages)
                    Log.d("ViewModel", "Indexed chunk ${index + 1}/${chunks.size} (${updatedMessages.size} messages)")
                }
            } else {
                Log.e("ViewModel", "Failed to get embeddings for chunk ${index + 1}")
                trySingleEmbeddingFallback(messageChunk, apiKey)
            }
        }
    }
    private suspend fun trySingleEmbeddingFallback(messages: List<Message>, apiKey: String) {
        Log.d("ViewModel", "  -> Trying single embedding fallback for ${messages.size} messages...")

        val updatedMessages = mutableListOf<Message>()

        messages.forEach { message ->
            val embedding = getEmbedding(message.messageText, apiKey)
            if (embedding != null) {
                updatedMessages.add(message.copy(embeddingJson = Json.encodeToString(embedding)))
            }
            // Small delay to avoid rate limiting
            kotlinx.coroutines.delay(200)
        }

        if (updatedMessages.isNotEmpty()) {
            repository.updateMessages(updatedMessages)
            Log.d("ViewModel", "✅ Single embedding fallback saved ${updatedMessages.size} messages")
        }
    }

    private fun extractContactName(fileName: String): String {
        return fileName.removePrefix("WhatsApp Chat with ")
            .removeSuffix(".txt")
            .trim()
    }

    fun deleteContact(contactName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteContactHistory(contactName)
            Log.d("ViewModel", "Deleted all messages for contact: $contactName")
        }
    }
}