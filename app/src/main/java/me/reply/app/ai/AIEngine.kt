package me.reply.app.ai

import android.util.Log
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
private const val CHAT_MODEL = "gemini-2.5-flash"

/**
 * Represents the outcome of any AI API call so callers can distinguish key faults
 * (which warrant key rotation) from transient/other errors (which do not).
 */
sealed class AiCallResult {
    data class Success(val replies: List<String>) : AiCallResult()
    /** 429 = rate-limited, 401/403 = invalid/no-permission */
    data class KeyFault(val httpCode: Int) : AiCallResult()
    /** 400, 500, IOException, etc. — do NOT rotate key */
    data class OtherError(val message: String) : AiCallResult()
}

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
/**
 * Batch embed [texts]. Returns (resultMap, keyFaultCode).
 * If a key fault (429/401/403) is hit mid-batch, stops immediately and returns (null, code)
 * so the caller can rotate the key and retry the whole chunk.
 */
fun getEmbeddingsInBatch(texts: List<String>, apiKey: String): Pair<Map<String, List<Float>>?, Int?> {
    val embeddingModel = "gemini-embedding-001"
    val result = mutableMapOf<String, List<Float>>()

    Log.d("AI_ENGINE", "  -> getEmbeddingsInBatch: Received ${texts.size} texts")

    val validPairs = texts.map { it to sanitizeTextForApi(it) }.filter { it.second.isNotBlank() }
    Log.d("AI_ENGINE", "  -> After sanitization check: ${validPairs.size} valid texts")
    if (validPairs.isEmpty()) return Pair(emptyMap(), null)

    for ((original, sanitized) in validPairs) {
        val (vector, errorCode) = getEmbeddingInternal(sanitized, apiKey, embeddingModel)
        when {
            vector != null -> result[original] = vector
            errorCode != null -> {
                // Key fault mid-batch — abort and let caller rotate
                Log.e("AI_ENGINE", "  -> Key fault ($errorCode) mid-batch. Aborting chunk.")
                return Pair(null, errorCode)
            }
            else -> Log.w("AI_ENGINE", "  -> Failed to embed (non-key reason): ${sanitized.take(30)}...")
        }
        Thread.sleep(100)
    }

    if (result.isEmpty()) {
        Log.e("AI_ENGINE", "❌ No embeddings generated from batch")
        return Pair(null, null)
    }
    Log.d("AI_ENGINE", "  -> ✅ Batch done: ${result.size}/${validPairs.size} embeddings")
    return Pair(result, null)
}

/**
 * Returns (vector, keyFaultCode).
 * vector is non-null on success.
 * keyFaultCode is 429 / 401 / 403 when the failure is the key's fault; null otherwise.
 */
private fun getEmbeddingInternal(text: String, apiKey: String, model: String): Pair<List<Float>?, Int?> {
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
    return try {
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        when {
            response.isSuccessful && body != null ->
                Pair(jsonParser.decodeFromString<EmbeddingResponse>(body).embedding.values, null)
            response.code in listOf(429, 401, 403) -> {
                Log.e("AI_ENGINE", "⚠️ Embedding key fault: HTTP ${response.code}")
                Pair(null, response.code)
            }
            else -> {
                Log.e("AI_ENGINE", "❌ Embedding failed: HTTP ${response.code} — $body")
                Pair(null, null)
            }
        }
    } catch (e: Exception) {
        Log.e("AI_ENGINE", "❌ Embedding exception: ${e.message}", e)
        Pair(null, null)
    }
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

/**
 * Returns (vector, keyFaultCode) — vector non-null on success,
 * keyFaultCode non-null when the HTTP failure is the key's fault.
 */
fun getEmbedding(text: String, apiKey: String): Pair<List<Float>?, Int?> {
    if (text.isBlank()) {
        Log.e("AI_ENGINE", "❌ getEmbedding: Text is blank")
        return Pair(null, null)
    }
    val sanitized = sanitizeTextForEmbedding(text)
    if (sanitized.isBlank()) {
        Log.e("AI_ENGINE", "❌ getEmbedding: Text blank after sanitization")
        return Pair(null, null)
    }
    return getEmbeddingInternal(sanitized, apiKey, "gemini-embedding-001")
}
/** Returns AiCallResult so the caller can detect key faults and rotate. */
fun generateSmartReplies(
    newMessage: AiMessage,
    history: List<AiMessage>,
    indexedHistory: Map<AiMessage, List<Float>>,
    apiKey: String,
    ourUser: String,
    precomputedVector: List<Float>? = null,
    profileLine: String? = null
): AiCallResult {
    val similarityThreshold = 0.75f
    val finalPrompt: String

    // Use precomputed vector when available — avoids a second embedding API call.
    val newMessageVector: List<Float>? = if (precomputedVector != null) {
        Log.d("AI_ENGINE", "🧠 Using pre-computed vector.")
        precomputedVector
    } else {
        Log.d("AI_ENGINE", "🧠 Getting embedding for new message...")
        val (vec, code) = getEmbedding(newMessage.content, apiKey)
        if (vec == null && code != null) return AiCallResult.KeyFault(code)
        vec
    }

    if (newMessageVector != null) {
        Log.d("AI_ENGINE", "🧠 Semantic search against ${indexedHistory.size} messages...")
        val bestMatch = findMostSimilarMessage(newMessageVector, indexedHistory)
        if (bestMatch != null && bestMatch.second > similarityThreshold) {
            Log.d("AI_ENGINE", "✅ Cache HIT! Score: ${"%.2f".format(bestMatch.second)}")
            val foundIndex = history.indexOfFirst {
                it.sender == bestMatch.first.sender && it.content == bestMatch.first.content
            }
            if (foundIndex != -1) {
                val contextWindow = history.subList(maxOf(0, foundIndex - 10), minOf(history.size, foundIndex + 11))
                finalPrompt = createPrompt("hit", contextWindow, history.takeLast(50), newMessage, ourUser, profileLine)
            } else {
                Log.w("AI_ENGINE", "Similar message not found in history, using miss path")
                finalPrompt = createPrompt("miss", emptyList(), history.takeLast(80), newMessage, ourUser, profileLine)
            }
        } else {
            Log.d("AI_ENGINE", "❌ Cache MISS.")
            finalPrompt = createPrompt("miss", emptyList(), history.takeLast(80), newMessage, ourUser, profileLine)
        }
    } else {
        Log.d("AI_ENGINE", "⚠ No vector — falling back to recent history.")
        finalPrompt = createPrompt("miss", emptyList(), history.takeLast(80), newMessage, ourUser, profileLine)
    }

    Log.d("AI_ENGINE", "📝 Sending prompt to Gemini...")
    return getAiChatResponse(finalPrompt, apiKey)
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

private fun createPrompt(
    type: String,
    context: List<AiMessage>,
    recent: List<AiMessage>,
    new: AiMessage,
    ourUser: String,
    profileLine: String? = null   // e.g. "Tone:casual Relation:family Note:we joke around"
): String {
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
    val profileSection = if (profileLine != null) "\nProfile: $profileLine" else ""

    return """
    You are a smart reply assistant. Generate 2 short reply suggestions for 'Me'. JSON array only. Ex: ["Sure!","On my way"]
    $profileSection
    $historyContent
    
    ## New Message
    $themSender ${new.content}
    
    ## My Reply Suggestions (as a JSON array):
    """.trimIndent()
}

/** Fallback: no embedding search — just recent history + chat. Returns AiCallResult. */
fun getAiChatResponseFallback(
    recentHistory: List<AiMessage>,
    newMessage: AiMessage,
    ourUser: String,
    apiKey: String,
    profileLine: String? = null
): AiCallResult {
    val prompt = createPrompt("miss", emptyList(), recentHistory, newMessage, ourUser, profileLine)
    return getAiChatResponse(prompt, apiKey)
}

/**
 * Core chat call. Returns:
 *  Success      — AI replied with a valid list
 *  KeyFault     — 429 / 401 / 403 (caller should rotate key and retry)
 *  OtherError   — 400, 500, IOException (caller should use hardcoded fallback)
 */
private fun getAiChatResponse(prompt: String, apiKey: String): AiCallResult {
    val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(60, TimeUnit.SECONDS)
        .build()
    val jsonParser = Json { ignoreUnknownKeys = true }
    val url = "$GOOGLE_API_BASE_URL/models/$CHAT_MODEL:generateContent?key=$apiKey"
    val requestObject = GeminiRequest(contents = listOf(Content(parts = listOf(Part(text = prompt)))))
    val requestBodyJson = jsonParser.encodeToString(requestObject)
    Log.d("AI_ENGINE", "--- PROMPT SENT TO GEMINI ---\n$prompt\n---------------------------")
    val request = Request.Builder()
        .url(url)
        .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
        .build()
    return try {
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        when {
            response.isSuccessful && body != null -> {
                val candidates = jsonParser.decodeFromString<GeminiResponse>(body).candidates
                if (candidates.isNotEmpty() && candidates.first().content.parts.isNotEmpty()) {
                    var aiText = candidates.first().content.parts.first().text ?: ""
                    if (aiText.contains("json")) {
                        aiText = aiText.substringAfter("json\n").substringBeforeLast("\n")
                    } else if (aiText.contains("```")) {
                        aiText = aiText.substringAfter("```").substringBeforeLast("```")
                    }
                    val replies = try {
                        jsonParser.decodeFromString<List<String>>(aiText.trim())
                    } catch (e: Exception) {
                        Log.e("AI_ENGINE", "JSON parse failed: ${e.message}")
                        extractRepliesManually(aiText)
                    }
                    AiCallResult.Success(replies)
                } else {
                    AiCallResult.OtherError("Empty candidates")
                }
            }
            response.code in listOf(429, 401, 403) -> {
                Log.e("AI_ENGINE", "⚠️ Chat key fault: HTTP ${response.code}")
                AiCallResult.KeyFault(response.code)
            }
            else -> {
                Log.e("AI_ENGINE", "❌ Chat failed: HTTP ${response.code} — $body")
                AiCallResult.OtherError("HTTP ${response.code}")
            }
        }
    } catch (e: Exception) {
        Log.e("AI_ENGINE", "❌ Chat exception: ${e.message}", e)
        AiCallResult.OtherError(e.message ?: "Unknown")
    }
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
