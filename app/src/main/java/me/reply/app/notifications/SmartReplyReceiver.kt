package me.reply.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.reply.app.data.Message
import me.reply.app.data.MessageRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SmartReplyReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_REPLY_PENDING_INTENT = "extra_reply_pending_intent"
        const val EXTRA_REMOTE_INPUT_KEY = "extra_remote_input_key"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastReplyKey: String? = null
    private var lastReplyTime: Long = 0

    override fun onReceive(context: Context, intent: Intent) {
        val replyText = intent.getStringExtra("key_reply_text") ?: return
        val contactName = intent.getStringExtra("key_contact_name") ?: "Unknown"

        val currentKey = "$contactName|$replyText"
        // Ignore if the same reply was sent in the last 2 seconds
        if (currentKey == lastReplyKey && (System.currentTimeMillis() - lastReplyTime < 2000)) {
            Log.w("SmartReplyReceiver", "Ignoring duplicate reply action.")
            // Still cancel the notification to clean up the UI
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            if (notificationId != 0) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId)
            }
            return
        }
        lastReplyKey = currentKey
        lastReplyTime = System.currentTimeMillis()

        val whatsAppPendingIntent = getPendingIntent(intent)
        val remoteInputKey = intent.getStringExtra(EXTRA_REMOTE_INPUT_KEY) ?: return

        sendWhatsAppReply(context, whatsAppPendingIntent, replyText, remoteInputKey)

        saveOurReplyToDatabase(context, contactName, replyText)

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
        Log.d("SmartReplyReceiver", "✅ Canceled our notification with ID: $notificationId")
    }

    private fun saveOurReplyToDatabase(context: Context, contactName: String, replyText: String) {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmartReplyReceiverEntryPoint::class.java
        )
        val repository = hiltEntryPoint.messageRepository()

        coroutineScope.launch {
            val messageToSave = Message(
                contactName = contactName,
                messageText = replyText,
                isSentByMe = true,
                embeddingJson = ""
            )
            repository.insertAndGetMessages(listOf(messageToSave))
            Log.d("SmartReplyReceiver", "✅ Successfully saved sent reply to database: '$replyText'")
        }
    }

    private fun getPendingIntent(intent: Intent): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_REPLY_PENDING_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_REPLY_PENDING_INTENT)
        }
    }

    private fun sendWhatsAppReply(context: Context, pi: PendingIntent?, replyText: String, remoteInputKey: String) {
        if (pi == null) {
            Log.e("SmartReplyReceiver", "WhatsApp PendingIntent is null, cannot send reply.")
            return
        }
        val replyIntent = Intent()
        val bundle = android.os.Bundle()
        bundle.putCharSequence(remoteInputKey, replyText)
        androidx.core.app.RemoteInput.addResultsToIntent(
            arrayOf(androidx.core.app.RemoteInput.Builder(remoteInputKey).build()),
            replyIntent,
            bundle
        )
        try {
            pi.send(context, 0, replyIntent)
            Log.d("SmartReplyReceiver", "✅ Sent reply to WhatsApp: '$replyText'")
        } catch (e: Exception) {
            Log.e("SmartReplyReceiver", "❌ Failed to send reply via PendingIntent", e)
        }
    }
}
