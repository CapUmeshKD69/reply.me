package me.reply.app.ai

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import me.reply.app.data.UserSettingsRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.sqrt
private const val GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
private const val CHAT_MODEL = "gemini-2.5-flash"

fun parseChatFile(fileContent: String): List<AiMessage> {
    val messages = mutableListOf<AiMessage>()

    val messagePattern = Regex(
        pattern = """^(?:\d{1,2}[/.]\d{1,2}[/.]\d{2,4},\s*\d{1,2}:\d{2}(?:\s*[APM]{2})?)\s*-\s*([^:]+):\s*(.+)""",
        option = RegexOption.IGNORE_CASE
    )

    fileContent.lines().forEach { line ->
        val matchResult = messagePattern.find(line)
        if (matchResult != null) {
            messages.add(AiMessage(
                sender = matchResult.groupValues[1].trim(),
                content = matchResult.groupValues[2].trim()
            ))
        } else if (messages.isNotEmpty() && line.isNotBlank()) {
            messages[messages.lastIndex] = messages.last().copy(content = messages.last().content + "\n" + line.trim())
        }
    }
    Log.d("ChatParser", "Successfully parsed ${messages.size} messages from the chat file.")
    return messages
}

private fun sanitizeTextForApi(text: String): String {

    val sanitized = text
        .replace("<Media omitted>", "[media]")
        .replace("This message was deleted", "[deleted]")
        .replace("<This message was edited>", "[edited]")
        .trim()


    if (sanitized.isBlank() && text.isNotBlank()) {
        Log.d("AI_ENGINE", "DEBUG: Message became empty after sanitization: '$text'")
    }

    return sanitized
}
fun getEmbeddingsInBatch(texts: List<String>, apiKey: String): Map<String, List<Float>>? {
    val embeddingModel = "gemini-embedding-001"
    val result = mutableMapOf<String, List<Float>>()
    
    Log.d("AI_ENGINE", "  -> getEmbeddingsInBatch: Received ${texts.size} texts")
    
    val textAndSanitizedPairs = texts.map { originalText ->
        originalText to sanitizeTextForApi(originalText)
    }
    
    val validPairs = textAndSanitizedPairs.filter { it.second.isNotBlank() }
    Log.d("AI_ENGINE", "  -> After sanitization check: ${validPairs.size} valid texts")
    
    if (validPairs.isEmpty()) {
        Log.d("AI_ENGINE", "  -> No valid texts to embed")
        return emptyMap()
    }

    for ((original, sanitized) in validPairs) {
        val embedding = getEmbeddingInternal(sanitized, apiKey, embeddingModel)
        if (embedding != null) {
            result[original] = embedding
        } else {
            Log.w("AI_ENGINE", "  -> Failed to embed text: ${sanitized.take(30)}...")
        }
        // Add 100ms delay between requests to avoid rate limiting
        Thread.sleep(100)
    }
    
    if (result.isEmpty()) {
        Log.e("AI_ENGINE", "❌ ERROR: No embeddings could be generated from batch")
        return null
    }
    
    Log.d("AI_ENGINE", "  -> ✅ Batch embedding successful, got ${result.size}/${validPairs.size} embeddings")
    return result
}

private fun getEmbeddingInternal(text: String, apiKey: String, model: String): List<Float>? {
    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()
    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = "$GOOGLE_API_BASE_URL/models/${model}:embedContent?key=$apiKey"
    
    val requestObject = EmbeddingRequest(
        model = "models/$model",
        content = Content(parts = listOf(Part(text = text)))
    )
    val requestBodyJson = jsonParser.encodeToString(requestObject)
    
    val request = Request.Builder()
        .url(url)
        .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
        .build()
    try {
        val response = client.newCall(request).execute()
        val responseBodyString = response.body?.string()

        if (response.isSuccessful && responseBodyString != null) {
            return jsonParser.decodeFromString<EmbeddingResponse>(responseBodyString).embedding.values
        } else if (response.code == 429) {
            Log.e("AI_ENGINE", "⚠️ Rate limit hit (429)! Quota exceeded. Please wait or upgrade to paid plan.")
            return null
        } else {
            Log.e("AI_ENGINE", "❌ Embedding failed! Code: ${response.code}")
            Log.e("AI_ENGINE", "   -> Response: $responseBodyString")
        }
    } catch (e: Exception) {
        Log.e("AI_ENGINE", "❌ CRITICAL: Exception during embedding: ${e.message}", e)
    }
    return null
}

private fun sanitizeTextForEmbedding(text: String): String {
    return text
        .replace("\"", "'")  // Replace double quotes with single quotes
        .replace("\\", "\\\\") // Escape backslashes
        .replace("\n", " ")  // Replace newlines with spaces
        .replace("\r", " ")  // Replace carriage returns
        .replace("\t", " ")  // Replace tabs
        .trim()
        .take(8000) // Limit length to avoid token limits
}

fun getEmbedding(text: String, apiKey: String): List<Float>? {
    if (text.isBlank()) {
        Log.e("AI_ENGINE", "❌ getEmbedding: Text is blank")
        return null
    }
    val sanitizedText = sanitizeTextForEmbedding(text)
    if (sanitizedText.isBlank()) {
        Log.e("AI_ENGINE", "❌ getEmbedding: Text became blank after sanitization")
        return null
    }
    return getEmbeddingInternal(sanitizedText, apiKey, "gemini-embedding-001")
}
fun generateSmartReplies(
    newMessage: AiMessage,
    history: List<AiMessage>,
    indexedHistory: Map<AiMessage, List<Float>>,
    apiKey: String,
    ourUser: String
): List<String> {
    val similarityThreshold = 0.75f
    var finalPrompt: String

    Log.d("AI_ENGINE", "🧠 Getting embedding for new message...")

    val newMessageVector = getEmbedding(newMessage.content,apiKey)

    if (newMessageVector != null) {
        Log.d("AI_ENGINE", "🧠 Performing semantic search against ${indexedHistory.size} indexed messages...")
        val bestMatch = findMostSimilarMessage(newMessageVector, indexedHistory)
        if (bestMatch != null && bestMatch.second > similarityThreshold) {
            Log.d("AI_ENGINE", "✅ Cache HIT! Found similar message with score: ${"%.2f".format(bestMatch.second)}")


            val foundIndex = history.indexOfFirst {
                it.sender == bestMatch.first.sender &&
                        it.content == bestMatch.first.content
            }
            if (foundIndex != -1) {
                val contextWindow = history.subList(maxOf(0, foundIndex - 10), minOf(history.size, foundIndex + 11))
                val recentHistory = history.takeLast(50)
                finalPrompt = createPrompt("hit", contextWindow, recentHistory, newMessage, ourUser)
            } else {
                Log.w("AI_ENGINE", "Similar message not found in current history, using fallback")
                val recentHistory = history.takeLast(80)
                finalPrompt = createPrompt("miss", emptyList(), recentHistory, newMessage, ourUser)
            }
        } else {
            Log.d("AI_ENGINE", "❌ Cache MISS. No highly similar message found.")
            val recentHistory = history.takeLast(80)
            finalPrompt = createPrompt("miss", emptyList(), recentHistory, newMessage, ourUser)
        }
    } else {
        Log.d("AI_ENGINE", "⚠ Could not get embedding for new message. Falling back to simple history.")
        val recentHistory = history.takeLast(80)
        finalPrompt = createPrompt("miss", emptyList(), recentHistory, newMessage, ourUser)
    }
    Log.d("AI_ENGINE", "📝 Final prompt selected. Sending to Gemini for reply generation...")
    return getAiChatResponse(finalPrompt , apiKey)
}
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

private fun createPrompt(type: String, context: List<AiMessage>, recent: List<AiMessage>, new: AiMessage, ourUser: String): String {
    val historyContent = if (type == "hit") {
        """
        ## Relevant Past Conversation
        ${formatHistoryForPrompt(context, ourUser)}
        
        ## Most Recent Messages
        ${formatHistoryForPrompt(recent, ourUser)}
        """.trimIndent()
    } else {
        "## Recent Messages\n${formatHistoryForPrompt(recent, ourUser)}"
    }

    val themSender = if (new.sender.equals(ourUser, ignoreCase = true)) "Me:" else "Them:"

    return """
    You are a smart reply assistant. Your task is to generate three short, casual reply suggestions that the user ('Me') could send next.
    Provide the output as a single, valid JSON array of strings. For example: ["Sounds good!", "What time?", "Can't make it."]
    
    $historyContent
    
    ## New Message
    $themSender ${new.content}
    
    ## My Reply Suggestions (as a JSON array):
    """.trimIndent()
}

private fun getAiChatResponse(prompt: String,apiKey: String) : List<String> {
    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()
    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = "$GOOGLE_API_BASE_URL/models/$CHAT_MODEL:generateContent?key=$apiKey"
    val requestObject = GeminiRequest(contents = listOf(Content(parts = listOf(Part(text = prompt)))))
    val requestBodyJson = jsonParser.encodeToString(requestObject)
    Log.d("AI_ENGINE", "--- PROMPT SENT TO GEMINI ---\n$prompt\n---------------------------")
    val request = Request.Builder().url(url).post(requestBodyJson.toRequestBody("application/json".toMediaType())).build()
    try {
        val firstResponse = client.newCall(request).execute()
        val firstBody = firstResponse.body?.string()
        var response = firstResponse
        var responseBodyString = firstBody

        if (response.code == 429) {
            Log.e("AI_ENGINE", "⚠️ Chat rate-limited (429). Retrying once after backoff.")
            Thread.sleep(2500)
            response.close()
            val retryResponse = client.newCall(request).execute()
            response = retryResponse
            responseBodyString = retryResponse.body?.string()
        }

        if (response.isSuccessful && responseBodyString != null) {
            val candidates = jsonParser.decodeFromString<GeminiResponse>(responseBodyString).candidates
            if (candidates.isNotEmpty() && candidates.first().content.parts.isNotEmpty()) {
                var aiText = candidates.first().content.parts.first().text ?: ""
                if (aiText.contains("json")) {
                    aiText = aiText.substringAfter("json\n").substringBeforeLast("\n")
                } else if (aiText.contains("```")) {
                    aiText = aiText.substringAfter("```").substringBeforeLast("```")
                }
                return try {
                    jsonParser.decodeFromString<List<String>>(aiText.trim())
                } catch (e: Exception) {
                    Log.e("AI_ENGINE", "JSON parsing failed, using fallback: ${e.message}")
                    extractRepliesManually(aiText)
                }
            }
        } else {
            Log.e("AI_ENGINE", "❌ Chat request failed! ${response.code}")
            Log.e("AI_ENGINE", "   -> Response: $responseBodyString")
        }
    } catch (e: Exception) {
        Log.e("AI_ENGINE", "❌ Exception during chat response: ${e.message}", e)
    }
    return listOf("Okay 👍", "Sounds good!", "Let me check")
}

private fun extractRepliesManually(text: String): List<String> {
    val replies = mutableListOf<String>()
    val patterns = listOf(
        """"([^"]*)"""".toRegex(),
        """'([^']*)'""".toRegex()
    )

    patterns.forEach { pattern ->
        pattern.findAll(text).forEach { match ->
            replies.add(match.groupValues[1])
        }
    }

    return if (replies.size >= 2) replies.take(3) else listOf("Okay 👍", "Sounds good!", "Let me check")
}
private fun formatHistoryForPrompt(messages: List<AiMessage>, ourUser: String): String {
    return messages.joinToString("\n") { msg ->
        val prefix = if (msg.sender.equals(ourUser, ignoreCase = true)) "Me:" else "Them:"
        "$prefix ${msg.content}"
    }
}
