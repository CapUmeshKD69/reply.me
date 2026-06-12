package me.reply.app.data

import kotlinx.serialization.Serializable

enum class KeyStatus { ACTIVE, RATE_LIMITED, INVALID, DISABLED }

@Serializable
data class ApiKey(
    val id: String,
    val key: String,
    val label: String,
    val status: KeyStatus,
    val failureCount: Int = 0,
    val addedAt: Long = 0L,
    val rateLimitedAt: Long = 0L   // epoch-ms when RATE_LIMITED was set; 0 otherwise
)
