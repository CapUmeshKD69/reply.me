package me.reply.app.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("contact_profiles", Context.MODE_PRIVATE)
    private val json  = Json { ignoreUnknownKeys = true }
    private val PREF_KEY = "profiles"

    private val _profilesFlow = MutableStateFlow<Map<String, ContactProfile>>(emptyMap())
    val profilesFlow: StateFlow<Map<String, ContactProfile>> = _profilesFlow.asStateFlow()

    init { _profilesFlow.value = loadAll() }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun loadAll(): Map<String, ContactProfile> {
        val raw = prefs.getString(PREF_KEY, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<ContactProfile>>(raw)
                .associateBy { it.contactName }
        } catch (e: Exception) {
            Log.e("ContactProfileRepo", "Failed to parse profiles: ${e.message}")
            emptyMap()
        }
    }

    private fun saveAll(profiles: Map<String, ContactProfile>) {
        prefs.edit()
            .putString(PREF_KEY, json.encodeToString(profiles.values.toList()))
            .apply()
        _profilesFlow.value = profiles
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getProfile(contactName: String): ContactProfile {
        return loadAll()[contactName] ?: ContactProfile(contactName = contactName)
    }

    fun saveProfile(profile: ContactProfile) {
        val all = loadAll().toMutableMap()
        all[profile.contactName] = profile
        saveAll(all)
        Log.d("ContactProfileRepo", "Saved profile for ${profile.contactName}")
    }

    fun setEnabled(contactName: String, enabled: Boolean) {
        val all     = loadAll().toMutableMap()
        val current = all[contactName] ?: ContactProfile(contactName = contactName)
        all[contactName] = current.copy(isEnabled = enabled)
        saveAll(all)
    }

    fun isEnabled(contactName: String): Boolean =
        loadAll()[contactName]?.isEnabled ?: true   // default: ON

    /**
     * Builds the compact profile line injected into the AI prompt.
     * Only non-default fields are emitted to keep token count low.
     * Returns null if nothing meaningful to add.
     *
     * Example output:
     *   "Tone:casual Relation:friend Lang:hinglish Len:brief Note:close friend, we joke around"
     */
    fun buildProfileLine(contactName: String): String? {
        val p     = loadAll()[contactName] ?: return null
        val parts = mutableListOf<String>()

        // Tone
        val toneStr = when (p.tone) {
            ToneType.CASUAL  -> "casual"
            ToneType.FORMAL  -> "formal"
            ToneType.NATURAL -> "natural"
            ToneType.CUSTOM  -> p.customTone.trim().take(30).ifBlank { null }
        }
        if (toneStr != null) parts.add("Tone:$toneStr")

        // Relation — skip default (FRIEND)
        when (p.relation) {
            RelationType.FAMILY       -> parts.add("Relation:family")
            RelationType.COLLEAGUE    -> parts.add("Relation:colleague")
            RelationType.PARTNER      -> parts.add("Relation:partner")
            RelationType.ACQUAINTANCE -> parts.add("Relation:acquaintance")
            RelationType.FRIEND       -> { /* default, omit */ }
        }

        // Language — skip default (STANDARD)
        when (p.language) {
            LanguageType.HINGLISH    -> parts.add("Lang:hinglish")
            LanguageType.SLANG_OK    -> parts.add("Lang:slang-ok")
            LanguageType.EMOJI_HEAVY -> parts.add("Lang:emoji")
            LanguageType.STANDARD    -> { /* default, omit */ }
        }

        // Length — skip default (MEDIUM)
        when (p.length) {
            LengthType.SHORT    -> parts.add("Len:brief")
            LengthType.DETAILED -> parts.add("Len:detailed")
            LengthType.MEDIUM   -> { /* default, omit */ }
        }

        // Context note — user typed, max 40 words
        if (p.context.isNotBlank()) {
            val note = p.context.trim().split(Regex("\\s+")).take(40).joinToString(" ")
            parts.add("Note:$note")
        }

        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}
