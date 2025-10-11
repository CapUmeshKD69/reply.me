package me.reply.app.notifications

import me.reply.app.data.MessageRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmartReplyReceiverEntryPoint {
    fun messageRepository(): MessageRepository
}