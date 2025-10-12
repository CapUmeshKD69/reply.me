package me.reply.app.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiMessage(
    val sender: String,
    val content: String
)


@Serializable
data class Part(val text: String)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class GeminiRequest(val contents: List<Content>)

@Serializable
data class Candidate(val content: Content)

@Serializable
data class GeminiResponse(val candidates: List<Candidate>)

@Serializable
data class Embedding(val values: List<Float>)

@Serializable
data class EmbeddingResponse(val embedding: Embedding)

@Serializable
data class EmbeddingRequest(
    val model: String = "models/text-embedding-001",
    val content: Content
)

@Serializable
data class BatchEmbeddingRequest(
    val requests: List<EmbeddingRequest>
)

@Serializable
data class BatchEmbeddingResponse(
    val embeddings: List<Embedding>
)
