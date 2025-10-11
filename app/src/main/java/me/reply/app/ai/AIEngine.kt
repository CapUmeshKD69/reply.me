package me.reply.app.ai


import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

var fullChatHistory = listOf<AiMessage>()
var indexedHistory = mapOf<AiMessage, List<Float>>()

private const val GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
private const val CHAT_MODEL = "gemini-2.5-pro"
// ===============================================================================================


/**
 * The main function simulates the app's entire lifecycle using the Google Gemini API.
 */
fun main() {
    // THE FIX: We now have two separate API keys for managing usage quotas.
    val embeddingApiKey = "AIzaSyCLIBLy8G6dEPN6dppIC7XL-EckyYq2oWk"
    val chatApiKey = "AIzaSyDZo1RCOH-V7Nh_NDW-5qkdiBkDq6IRTsA"

    // --- The rest of your simulation code is perfect and remains unchanged ---

    println("--- Step 1: Reading and Parsing User's Chat History ---")

    val chatFile = File("WhatsApp Chat with Om Prakash.txt")
    if (!chatFile.exists()) {
        println("⚠️ ERROR: Chat file not found or is empty. Exiting.")
        return
    }
    val fileContent = chatFile.readText()
    val parsedMessages = parseChatFile(fileContent)

    fullChatHistory = parsedMessages
    println("✅ Parser finished. Found ${fullChatHistory.size} messages.\n")

    println("--- Step 2: Running the One-Time Indexing Process (Simulated) ---")
    // THE FIX: The indexing process is given the specific key for embeddings.
    val vectors = runIndexingProcess(fullChatHistory, embeddingApiKey)
    if (vectors != null) {
        indexedHistory = vectors
        println("\n✅ Indexing complete! Embeddings created for ${indexedHistory.size} messages.\n")
    } else {
        println("\n⚠️ ERROR: Indexing failed. Smart replies may be less accurate.\n")
    }
    if (indexedHistory.isNotEmpty()) {
        indexedHistory.forEach { (message, vector) ->
            println("-> For message: \"${message.content}\" from ${message.sender}")
            println("   Vector (first 5 values): ${vector.take(5)}...")
            println("-------------------------------------------------")
        }
    } else {
        println("   No embeddings were generated to display.")
    }

    println("\n--- Step 3: A New Message Arrives ---")
    val newIncomingMessage = AiMessage("10 : 00 AM", "Om prakash", " bhai campus ghoomne chalega ?")
    println("New message from ${newIncomingMessage.sender}: \"${newIncomingMessage.content}\"")
    println("Calling the smart reply engine...\n")

    // THE FIX: The main engine function is now given both keys to do its job.
    val smartReplies = generateSmartReplies(
        newMessage = newIncomingMessage,
        history = fullChatHistory,
        indexedHistory = indexedHistory,
        embeddingApiKey = embeddingApiKey,
        chatApiKey = chatApiKey
    )

    println("\n--- Step 4: Displaying the Final Smart Replies ---")
    smartReplies.forEachIndexed { index, reply ->
        println("${index + 1}. $reply")
    }
}

// ===============================================================================================
//            UNCHANGED LOGIC - Your core simulation and parsing is identical
// ===============================================================================================

fun runIndexingProcess(history: List<AiMessage>, apiKey: String): Map<AiMessage, List<Float>>? {
    println("  -> Breaking history into chunks...")
    val historyToIndex = history.takeLast(700)
    val chunks = historyToIndex.chunked(100)
    val finalEmbeddings = mutableMapOf<AiMessage, List<Float>>()

    chunks.forEachIndexed { index, messageChunk ->
        println("  -> Processing chunk ${index + 1} of ${chunks.size}...")
        val textsToEmbed = messageChunk.map { it.content }
        val embeddingsMap = getEmbeddingsInBatch(textsToEmbed, apiKey)

        if (embeddingsMap != null) {
            messageChunk.forEach { message ->
                embeddingsMap[message.content]?.let { vector ->
                    finalEmbeddings[message] = vector
                }
            }
        } else {
            println("  ⚠️ Failed to process chunk ${index + 1}. Stopping indexing.")
            return null
        }
    }
    return finalEmbeddings
}

fun parseChatFile(fileContent: String): List<AiMessage> {
    val messages = mutableListOf<AiMessage>()
    val messagePattern = Regex("(\\d{2}/\\d{2}/\\d{2}), (.+?) - (.+?): (.*)")
    fileContent.lines().forEach { line ->
        val matchResult = messagePattern.find(line)
        if (matchResult != null) {
            messages.add(AiMessage(
                timestamp = "${matchResult.groupValues[1]}, ${matchResult.groupValues[2]}",
                sender = matchResult.groupValues[3],
                content = matchResult.groupValues[4].trim()
            ))
        } else if (messages.isNotEmpty()) {
            messages[messages.lastIndex] = messages.last().copy(content = messages.last().content + "\n" + line.trim())
        }
    }
    return messages
}


// ===============================================================================================
//                       PURE GEMINI AI ENGINE (DUAL KEY & TIMEOUT FIX)
// ===============================================================================================

fun getEmbeddingsInBatch(texts: List<String>, apiKey: String): Map<String, List<Float>>? {
    // THE FIX: Configure the client with a longer timeout.
    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()

    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = "$GOOGLE_API_BASE_URL/models/text-embedding-004:batchEmbedContents?key=$apiKey"
    val requests = texts.map { text ->
        EmbeddingRequest(
            model = "models/text-embedding-004",
            content = Content(parts = listOf(Part(text = text)))
        )
    }
    val batchRequest = BatchEmbeddingRequest(requests = requests)
    val requestBodyJson = jsonParser.encodeToString(batchRequest)
    val request = Request.Builder().url(url).post(requestBodyJson.toRequestBody("application/json".toMediaType())).build()
    try {
        val response = client.newCall(request).execute()
        val responseBodyString = response.body?.string()
        if (response.isSuccessful && responseBodyString != null) {
            val batchResponse = jsonParser.decodeFromString<BatchEmbeddingResponse>(responseBodyString)
            return texts.zip(batchResponse.embeddings.map { it.values }).toMap()
        } else {
            println("❌ [Google] Batch Embedding request failed! Code: ${response.code}")
            println("   -> Response Body: $responseBodyString")
        }
    } catch (e: Exception) {
        println("❌ [Google] An exception occurred during batch embedding: ${e.message}")
    }
    return null
}

fun getEmbedding(text: String, apiKey: String): List<Float>? {
    // THE FIX: Configure the client with a longer timeout.
    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()

    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = "$GOOGLE_API_BASE_URL/models/text-embedding-004:embedContent?key=$apiKey"
    val requestBodyJson = """{"model": "models/text-embedding-004", "content": {"parts": [{"text": "$text"}]}}"""
    val request = Request.Builder().url(url).post(requestBodyJson.toRequestBody("application/json".toMediaType())).build()
    try {
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            return jsonParser.decodeFromString<EmbeddingResponse>(response.body!!.string()).embedding.values
        }
    } catch (e: Exception) { /* Fails silently */ }
    return null
}

// THE FIX: This function now accepts two specific keys for its two different jobs.
fun generateSmartReplies(
    newMessage: AiMessage,
    history: List<AiMessage>,
    indexedHistory: Map<AiMessage, List<Float>>,
    embeddingApiKey: String,
    chatApiKey: String
): List<String> {
    val similarityThreshold = 0.75f
    var finalPrompt: String
    println("🧠 Getting embedding for new message...")
    // THE FIX: It uses the embedding key for this task.
    val newMessageVector = getEmbedding(newMessage.content, embeddingApiKey)
    if (newMessageVector != null) {
        println("🧠 Performing fast, local semantic search against ${indexedHistory.size} indexed messages...")
        val bestMatch = findMostSimilarMessage(newMessageVector, indexedHistory)
        if (bestMatch != null && bestMatch.second > similarityThreshold) {
            println("✅ Cache HIT! Found a similar message with score: ${"%.2f".format(bestMatch.second)}")
            val foundIndex = history.indexOf(bestMatch.first)
            val contextWindow = history.subList(maxOf(0, foundIndex - 10), minOf(history.size, foundIndex + 11))
            val recentHistory = history.takeLast(50)
            finalPrompt = createPrompt("hit", contextWindow, recentHistory, newMessage)
        } else {
            println("❌ Cache MISS. No highly similar message found.")
            val recentHistory = history.takeLast(80)
            finalPrompt = createPrompt("miss", emptyList(), recentHistory, newMessage)
        }
    } else {
        println("⚠️ Could not get embedding for new message. Falling back to simple history.")
        val recentHistory = history.takeLast(80)
        finalPrompt = createPrompt("miss", emptyList(), recentHistory, newMessage)
    }
    println("\n📝 Final prompt selected. Sending to Gemini for reply generation...")
    // THE FIX: It uses the chat key for this task.
    return getAiChatResponse(finalPrompt, chatApiKey)
}

private fun getAiChatResponse(prompt: String, apiKey: String): List<String> {
    // THE FIX: Configure the client with a longer timeout. This is the main fix for your error.
    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()

    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = "$GOOGLE_API_BASE_URL/models/$CHAT_MODEL:generateContent?key=$apiKey"

    val requestObject = GeminiRequest(contents = listOf(Content(parts = listOf(Part(text = prompt)))))
    val requestBodyJson = jsonParser.encodeToString(requestObject)

    println("--- PROMPT SENT TO $CHAT_MODEL ---\n$prompt\n---------------------------")

    val request = Request.Builder().url(url).post(requestBodyJson.toRequestBody("application/json".toMediaType())).build()
    try {
        val response = client.newCall(request).execute()
        val responseBodyString = response.body?.string()
        if (response.isSuccessful && responseBodyString != null) {
            val candidates = jsonParser.decodeFromString<GeminiResponse>(responseBodyString).candidates
            if (candidates.isNotEmpty()) {
                var aiText = candidates.first().content.parts.first().text
                if (aiText.contains("```json")) {
                    aiText = aiText.substringAfter("```json\n").substringBeforeLast("\n```")
                }
                return jsonParser.decodeFromString<List<String>>(aiText)
            }
        } else {
            println("❌ [Google] Chat request failed! Code: ${response.code}")
            println("   Error Body: $responseBodyString")
        }
    } catch (e: Exception) {
        println("❌ [Google] An exception occurred during chat response: ${e.message}")
        e.printStackTrace()
    }
    return listOf("Error: Could not get replies.")
}


// ===============================================================================================
//            UNCHANGED LOGIC - Your core helper functions are identical
// ===============================================================================================

fun findMostSimilarMessage(newMessageVector: List<Float>, indexedHistory: Map<AiMessage, List<Float>>): Pair<AiMessage, Float>? {
    var bestMatch: AiMessage? = null
    var highestSimilarity = -1.0f
    for ((historicalMessage, historicalVector) in indexedHistory) {
        if(historicalVector.isEmpty()) continue
        val similarity = cosineSimilarity(newMessageVector, historicalVector)
        if (similarity > highestSimilarity) {
            highestSimilarity = similarity
            bestMatch = historicalMessage
        }
    }
    return if (bestMatch != null) Pair(bestMatch, highestSimilarity) else null
}

fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Float {
    val dotProduct = vec1.zip(vec2).sumOf { (a, b) -> (a * b).toDouble() }
    val magnitude1 = sqrt(vec1.sumOf { (it * it).toDouble() })
    val magnitude2 = sqrt(vec2.sumOf { (it * it).toDouble() })
    if (magnitude1 == 0.0 || magnitude2 == 0.0) return 0f
    return (dotProduct / (magnitude1 * magnitude2)).toFloat()
}

private fun createPrompt(type: String, context: List<AiMessage>, recent: List<AiMessage>, new: AiMessage): String {
    val ourUser = "Umesh"
    val historyContent = if (type == "hit") {
        """
        ## Relevant Past Conversation
        ${formatHistoryForPrompt(context)}
        ## Most Recent Messages
        ${formatHistoryForPrompt(recent)}
        """.trimIndent()
    } else {
        "## Recent Messages\n${formatHistoryForPrompt(recent)}"
    }
    return """
    You are a smart reply assistant. Your task is to generate three short, casual reply suggestions that '$ourUser' could send next.
    Provide the output as a single, valid JSON array of strings. the oold chat have 2 section , the first section will have the previous similar messaage and the 2nd section will have most recent message ,take 1st for reference and finding answer and second for tone 
    $historyContent
    ${new.sender}: ${new.content}
    ## $ourUser's Reply Suggestions (as a JSON array):
    """.trimIndent()
}

private fun formatHistoryForPrompt(messages: List<AiMessage>): String {
    return messages.joinToString("\n") { "${it.sender}: ${it.content}" }
}

