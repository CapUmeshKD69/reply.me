package me.reply.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow



@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAndGetIds(messages: List<Message>): List<Long>

    @Update
    suspend fun updateMessages(messages: List<Message>)

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getMessagesByIds(ids: List<Long>): List<Message>

    @Query("SELECT DISTINCT contact_name FROM messages ORDER BY contact_name ASC")
    fun getImportedContacts(): Flow<List<String>>

    @Query("SELECT * FROM messages WHERE contact_name = :contactName")
    suspend fun getAllMessagesForContact(contactName: String): List<Message>

    @Query("DELETE FROM messages WHERE contact_name = :contactName")
    suspend fun deleteMessagesForContact(contactName: String)

    @Query("SELECT COUNT(*) FROM messages WHERE contact_name = :contactName LIMIT 1")
    suspend fun isContactImported(contactName: String): Int
}

