package me.reply.app.data
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class MessageRepository @Inject constructor(private val messageDao: MessageDao) {
    fun getImportedContacts() = messageDao.getImportedContacts()
    suspend fun getAllMessagesForContact(contactName: String): List<Message> {
        return messageDao.getAllMessagesForContact(contactName)
    }
    suspend fun insertAndGetMessages(messages: List<Message>): List<Message> {
        val newIds = messageDao.insertAndGetIds(messages)
        if (newIds.isNotEmpty()) {
            return messageDao.getMessagesByIds(newIds)
        }
        return emptyList()
    }
    suspend fun updateMessages(messages: List<Message>) {
        messageDao.updateMessages(messages)
    }
    suspend fun deleteContactHistory(contactName: String) {
        messageDao.deleteMessagesForContact(contactName)
    }
    suspend fun isContactImported(contactName: String): Boolean {

        return messageDao.isContactImported(contactName) > 0
    }
}

