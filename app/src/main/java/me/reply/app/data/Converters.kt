package me.reply.app.data

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromFloatList(value: List<Float>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toFloatList(value: String): List<Float> {
        return Json.decodeFromString(value)
    }
}