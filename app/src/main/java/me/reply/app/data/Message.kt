package me.reply.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "contact_name")
    val contactName: String,

    @ColumnInfo(name = "message_text")
    val messageText: String,

    @ColumnInfo(name = "is_sent_by_me")
    val isSentByMe: Boolean,

    @ColumnInfo(name = "embedding_json")
    val embeddingJson: String
)