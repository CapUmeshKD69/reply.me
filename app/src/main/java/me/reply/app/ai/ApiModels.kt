package me.reply.app.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiMessage(
    val sender: String,
    val content: String
)

// --- Gemini API Models ---
@Serializable
data class Part(val text: String)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class GeminiRequest(val contents: List<Content>)

@Serializable
data class Candidate(val content: Content)
