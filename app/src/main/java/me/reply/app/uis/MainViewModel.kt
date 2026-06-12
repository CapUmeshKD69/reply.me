package me.reply.app.uis

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.reply.app.ai.AiCallResult
import me.reply.app.ai.AiMessage
import me.reply.app.ai.getEmbedding
import me.reply.app.ai.getEmbeddingsInBatch
import me.reply.app.ai.parseChatFile
import me.reply.app.data.ApiKeyRepository
import me.reply.app.data.ContactProfileRepository
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

private val HARDCODED_FALLBACK = listOf("Okay 👍", "Sounds good!", "Let me check")

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val apiKeyRepository: ApiKeyRepository,
    val contactProfileRepository: ContactProfileRepository
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
            val apiKey = apiKeyRepository.getActiveKey()
            if (apiKey == null) {
                Log.e("ViewModel", "No active API key. Cannot start indexing.")
                return@launch
            }
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
                        )
                        val filteredMessages = parsedAiMessages.filterNot {
                            it.content in ignoredMessages
                        }
                        if (filteredMessages.isEmpty()) {
                            Log.d("ViewModel", "File contained only ignored messages. Skipping.")
                            return@forEach
                        }

                        val messageLimit = 2000
                        val finalMessages = if (filteredMessages.size > messageLimit) {
                            Log.d("ViewModel", "Large file (${filteredMessages.size}). Taking last $messageLimit.")
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
                        Log.d("ViewModel", "Saved ${savedMessages.size} messages for $contactName")

                        runIndexingProcess(savedMessages, apiKey)
                    }
                } catch (e: Exception) {
                    Log.e("ViewModel", "Error processing file $fileName: ${e.message}")
                }
            }
            _indexingProgress.value = null
            Log.d("ViewModel", "Finished processing all files.")
        }
    }

    private fun detectUserNameFromParsedMessages(
        messages: List<AiMessage>,
        contactName: String
    ): String {
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

    private suspend fun runIndexingProcess(messagesToIndex: List<Message>, initialApiKey: String) {
        Log.d("ViewModel", "Starting indexing for ${messagesToIndex.size} messages...")
        var currentApiKey = initialApiKey
        val chunks = messagesToIndex.chunked(80)

        chunks.forEachIndexed { index, chunk ->
            _indexingProgress.value = (index + 1) to chunks.size
            val textsToEmbed = chunk.map { it.messageText }

            // Attempt batch embedding with key-rotation on fault
            val (embeddingsMap, keyFaultCode) = getEmbeddingsInBatch(textsToEmbed, currentApiKey)

            if (keyFaultCode != null) {
                // Current key hit a fault — report and rotate
                apiKeyRepository.reportKeyError(currentApiKey, keyFaultCode)
                val nextKey = apiKeyRepository.rotateToNextActiveKey()
                if (nextKey == null) {
                    Log.e("ViewModel", "All API keys exhausted during indexing. Stopping.")
                    return
                }
                currentApiKey = nextKey
                Log.d("ViewModel", "Rotated key. Retrying chunk ${index + 1} with new key.")
                // Retry chunk once with new key
                val (retryMap, _) = getEmbeddingsInBatch(textsToEmbed, currentApiKey)
                if (retryMap != null) {
                    saveEmbeddings(chunk, retryMap)
                } else {
                    Log.e("ViewModel", "Chunk ${index + 1} failed after key rotation. Skipping.")
                }
            } else if (embeddingsMap != null) {
                saveEmbeddings(chunk, embeddingsMap)
                Log.d("ViewModel", "Indexed chunk ${index + 1}/${chunks.size}")
            } else {
                Log.e("ViewModel", "Non-key failure for chunk ${index + 1}. Trying single fallback.")
                trySingleEmbeddingFallback(chunk, currentApiKey)
            }
        }
    }

    private suspend fun saveEmbeddings(messages: List<Message>, embeddingsMap: Map<String, List<Float>>) {
        val updated = messages.mapNotNull { msg ->
            embeddingsMap[msg.messageText]?.let { vec ->
                msg.copy(embeddingJson = Json.encodeToString(vec))
            }
        }
        if (updated.isNotEmpty()) repository.updateMessages(updated)
    }

    private suspend fun trySingleEmbeddingFallback(messages: List<Message>, apiKey: String) {
        Log.d("ViewModel", "  -> Single embedding fallback for ${messages.size} messages...")
        val updated = mutableListOf<Message>()
        messages.forEach { msg ->
            val (vec, _) = getEmbedding(msg.messageText, apiKey)
            if (vec != null) updated.add(msg.copy(embeddingJson = Json.encodeToString(vec)))
            kotlinx.coroutines.delay(200)
        }
        if (updated.isNotEmpty()) {
            repository.updateMessages(updated)
            Log.d("ViewModel", "  -> Single fallback saved ${updated.size} messages")
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
            Log.d("ViewModel", "Deleted all messages for: $contactName")
        }
    }
}
