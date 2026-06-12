package me.reply.app.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---------- Result types for callers ----------

sealed class AddKeyResult {
    object Added : AddKeyResult()
    object Duplicate : AddKeyResult()
    object CapReached : AddKeyResult()
}

sealed class DeleteKeyResult {
    object Deleted : DeleteKeyResult()
    object WasActiveRotated : DeleteKeyResult()
    object LastKeyDeleted : DeleteKeyResult()
}

// ---------- Repository ----------

@Singleton
class ApiKeyRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("api_key_pool", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val PREF_KEY_LIST    = "key_list"
    private val PREF_ACTIVE_IDX  = "active_key_index"
    private val MAX_KEYS         = 5
    private val RATE_LIMIT_MS    = 24L * 60 * 60 * 1000   // 24 hours

    /** Hot flow consumed by the UI — emits on every write */
    private val _keysFlow = MutableStateFlow<List<ApiKey>>(emptyList())
    val keysFlow: StateFlow<List<ApiKey>> = _keysFlow.asStateFlow()

    /** Emits the ID of the currently active key — updates instantly on setActiveKey */
    private val _activeKeyIdFlow = MutableStateFlow<String?>(null)
    val activeKeyIdFlow: StateFlow<String?> = _activeKeyIdFlow.asStateFlow()

    init {
        val loaded = loadKeys()
        _keysFlow.value = loaded
        _activeKeyIdFlow.value = loaded.getOrNull(getActiveIndex())?.id
    }

    // ---- Private helpers ----

    private fun loadKeys(): MutableList<ApiKey> {
        val raw = prefs.getString(PREF_KEY_LIST, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<ApiKey>>(raw).toMutableList()
        } catch (e: Exception) {
            Log.e("ApiKeyRepo", "Failed to parse key list: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveKeys(keys: List<ApiKey>) {
        prefs.edit().putString(PREF_KEY_LIST, json.encodeToString(keys)).apply()
        _keysFlow.value = keys.toList()
    }

    private fun getActiveIndex(): Int = prefs.getInt(PREF_ACTIVE_IDX, 0)
    private fun setActiveIndex(i: Int) {
        prefs.edit().putInt(PREF_ACTIVE_IDX, i).apply()
        // Keep the active-key flow in sync so the UI recomposes immediately
        _activeKeyIdFlow.value = _keysFlow.value.getOrNull(i)?.id
    }

    /**
     * Mutates [keys] in place: any RATE_LIMITED key whose 24-hour window has passed
     * is restored to ACTIVE. Returns true if anything changed.
     */
    private fun restoreExpiredRateLimits(keys: MutableList<ApiKey>): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        keys.forEachIndexed { i, k ->
            if (k.status == KeyStatus.RATE_LIMITED &&
                k.rateLimitedAt > 0 &&
                now - k.rateLimitedAt >= RATE_LIMIT_MS
            ) {
                keys[i] = k.copy(status = KeyStatus.ACTIVE, rateLimitedAt = 0L, failureCount = 0)
                changed = true
                Log.d("ApiKeyRepo", "Auto-restored: ${maskedKey(k.key)}")
            }
        }
        return changed
    }

    // ---- Public API ----

    /**
     * Returns the API key string of the current active slot, or null if no ACTIVE key exists.
     * Auto-restores expired rate-limited keys on every call (cheap timestamp check).
     */
    fun getActiveKey(): String? {
        val keys = loadKeys()
        if (keys.isEmpty()) return null
        if (restoreExpiredRateLimits(keys)) saveKeys(keys)

        val idx = getActiveIndex().coerceIn(0, keys.lastIndex)
        if (keys[idx].status == KeyStatus.ACTIVE) return keys[idx].key

        // Current slot isn't active — find any active slot
        val firstActive = keys.indexOfFirst { it.status == KeyStatus.ACTIVE }
        if (firstActive == -1) return null
        setActiveIndex(firstActive)
        return keys[firstActive].key
    }

    fun hasAnyActiveKey(): Boolean = getActiveKey() != null

    /**
     * Returns the ID of the key that is currently marked as the active slot.
     * Used by the UI to highlight the active card.
     */
    fun getActiveKeyId(): String? {
        val keys = loadKeys()
        if (keys.isEmpty()) return null
        val idx = getActiveIndex().coerceIn(0, keys.lastIndex)
        return keys.getOrNull(idx)?.id
    }

    /**
     * Called when an HTTP error proves the current key is at fault.
     * 429 → RATE_LIMITED (auto-recovers after 24 h)
     * 401 / 403 → INVALID (permanent until user removes it)
     * Any other code → ignored (not the key's fault)
     */
    fun reportKeyError(keyString: String, httpCode: Int) {
        val keys = loadKeys()
        val idx = keys.indexOfFirst { it.key == keyString }
        if (idx == -1) return

        val newStatus = when (httpCode) {
            429       -> KeyStatus.RATE_LIMITED
            401, 403  -> KeyStatus.INVALID
            else      -> return
        }
        val now = System.currentTimeMillis()
        keys[idx] = keys[idx].copy(
            status        = newStatus,
            failureCount  = keys[idx].failureCount + 1,
            rateLimitedAt = if (newStatus == KeyStatus.RATE_LIMITED) now else keys[idx].rateLimitedAt
        )
        saveKeys(keys)
        Log.d("ApiKeyRepo", "Marked ${maskedKey(keyString)} as $newStatus (HTTP $httpCode)")
    }

    /**
     * Advances the active index to the next ACTIVE key (wraps around).
     * Returns the new key string, or null if no other ACTIVE key exists.
     */
    fun rotateToNextActiveKey(): String? {
        val keys = loadKeys()
        if (keys.isEmpty()) return null
        restoreExpiredRateLimits(keys)

        val current = getActiveIndex().coerceIn(0, keys.lastIndex)
        for (offset in 1..keys.size) {
            val tryIdx = (current + offset) % keys.size
            if (keys[tryIdx].status == KeyStatus.ACTIVE) {
                setActiveIndex(tryIdx)
                Log.d("ApiKeyRepo", "Rotated active key → index $tryIdx")
                return keys[tryIdx].key
            }
        }
        return null
    }

    fun addKey(keyString: String, label: String): AddKeyResult {
        val keys = loadKeys()
        if (keys.size >= MAX_KEYS)                        return AddKeyResult.CapReached
        if (keys.any { it.key == keyString })             return AddKeyResult.Duplicate

        val newKey = ApiKey(
            id           = UUID.randomUUID().toString(),
            key          = keyString,
            label        = label.ifBlank { "Account ${keys.size + 1}" },
            status       = KeyStatus.ACTIVE,
            failureCount = 0,
            addedAt      = System.currentTimeMillis(),
            rateLimitedAt = 0L
        )
        val updated = keys + newKey
        saveKeys(updated)
        // Make this the active key if it's the very first one
        if (updated.size == 1) setActiveIndex(0)
        return AddKeyResult.Added
    }

    fun deleteKey(id: String): DeleteKeyResult {
        val keys = loadKeys()
        val deleteIdx = keys.indexOfFirst { it.id == id }
        if (deleteIdx == -1) return DeleteKeyResult.Deleted

        val wasLast   = keys.size == 1
        val activeIdx = getActiveIndex().coerceIn(0, keys.lastIndex)
        val wasActive = deleteIdx == activeIdx

        val updated = keys.toMutableList().also { it.removeAt(deleteIdx) }

        return when {
            wasLast -> {
                saveKeys(updated)
                setActiveIndex(0)
                DeleteKeyResult.LastKeyDeleted
            }
            wasActive -> {
                // Land on the same index (now points to the next item) or clamp
                val newIdx = deleteIdx.coerceIn(0, updated.lastIndex)
                setActiveIndex(newIdx)
                saveKeys(updated)
                DeleteKeyResult.WasActiveRotated
            }
            else -> {
                // If deleted item was before the active, shift active index back by 1
                if (deleteIdx < activeIdx) setActiveIndex(activeIdx - 1)
                saveKeys(updated)
                DeleteKeyResult.Deleted
            }
        }
    }

    /** Manually set which key slot is active (user override). */
    fun setActiveKey(id: String) {
        val keys = loadKeys()
        val idx = keys.indexOfFirst { it.id == id }
        if (idx != -1) {
            setActiveIndex(idx)
            Log.d("ApiKeyRepo", "User manually set active key → index $idx")
        }
    }

    /** Returns a display-safe masked version of a key, e.g. "AIzaSy...kXYZ". */
    fun maskedKey(key: String): String =
        if (key.length > 10) "${key.take(6)}...${key.takeLast(4)}" else "***"
}
