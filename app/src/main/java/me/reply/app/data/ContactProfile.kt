package me.reply.app.data

import kotlinx.serialization.Serializable

enum class ToneType   { CASUAL, FORMAL, NATURAL, CUSTOM }
enum class RelationType { FRIEND, FAMILY, COLLEAGUE, PARTNER, ACQUAINTANCE }
enum class LanguageType { STANDARD, HINGLISH, SLANG_OK, EMOJI_HEAVY }
enum class LengthType   { SHORT, MEDIUM, DETAILED }

@Serializable
data class ContactProfile(
    val contactName: String,
    val isEnabled: Boolean       = true,
    val tone: ToneType           = ToneType.CASUAL,
    val customTone: String       = "",           // only used when tone == CUSTOM
    val relation: RelationType   = RelationType.FRIEND,
    val language: LanguageType   = LanguageType.STANDARD,
    val length: LengthType       = LengthType.MEDIUM,
    val context: String          = ""            // user-typed, max 40 words
)
