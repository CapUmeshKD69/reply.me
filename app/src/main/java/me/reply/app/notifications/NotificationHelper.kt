package me.reply.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Intent
import me.reply.app.R

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val channelId = "smart_reply_channel"
    private val notificationId = 1001
    private val TAG = "NotificationHelper"

    init {
        Log.d(TAG, "NotificationHelper initialized")
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Smart Replies"
            val descriptionText = "Notifications containing AI-generated smart replies"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $channelId")
        } else {
            Log.d(TAG, "Notification channel not needed (pre-Oreo)")
        }
    }

    fun showSmartReplyNotification(
        contactName: String,
        messageText: String,
        replies: List<String>,
        originalNotification: Notification,
        ourUserName: String
    ) {
        Log.d(TAG, "showSmartReplyNotification called")
        Log.d(TAG, "Contact: $contactName, Message: $messageText")
        Log.d(TAG, "Available replies: $replies")

        val replyAction = findWhatsAppReplyAction(originalNotification)

        if (replyAction == null) {
            Log.e(TAG, "❌ WhatsApp reply action NOT FOUND - Custom notification cannot be shown")
            Log.d(TAG, "Original notification actions: ${originalNotification.actions?.size ?: 0}")

            // Debug: Print all action labels
            originalNotification.actions?.forEachIndexed { index, action ->
                Log.d(
                    TAG,
                    "Action $index: ${action.title} - RemoteInputs: ${action.remoteInputs?.size ?: 0}"
                )
                action.remoteInputs?.forEachIndexed { riIndex, remoteInput ->
                    Log.d(TAG, "  RemoteInput $riIndex - ResultKey: '${remoteInput.resultKey}'")
                }
            }
            return
        } else {
            Log.d(TAG, "✅ WhatsApp reply action FOUND")
            Log.d(TAG, "Reply action title: ${replyAction.title}")
            Log.d(TAG, "Reply action remote inputs: ${replyAction.remoteInputs?.size ?: 0}")
            replyAction.remoteInputs?.forEachIndexed { index, remoteInput ->
                Log.d(TAG, "RemoteInput $index - ResultKey: '${remoteInput.resultKey}'")
            }
        }

        val smartReplyActions = replies.take(3).map { replyText ->
            Log.d(TAG, "Creating reply action for: '$replyText'")
            createReplyAction(replyText, replyAction, notificationId, contactName, ourUserName)
        }

        Log.d(TAG, "Created ${smartReplyActions.size} smart reply actions")

        val person = Person.Builder().setName(contactName).build()
        val style = NotificationCompat.MessagingStyle(person)
            .addMessage(messageText, System.currentTimeMillis(), person)

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        smartReplyActions.forEach { notificationBuilder.addAction(it) }

        // Step 5: Notification ko "Post Office" (NotificationManager) ko bhejna.
        try {
            notificationManager.notify(notificationId, notificationBuilder.build())
            Log.d(TAG, "✅ Custom notification SUCCESSFULLY shown with ID: $notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show custom notification: ${e.message}", e)
        }
    }

    private fun findWhatsAppReplyAction(notification: Notification): Notification.Action? {
        Log.d(TAG, "findWhatsAppReplyAction: Searching for WhatsApp reply action...")

        val actions = notification.actions
        if (actions == null || actions.isEmpty()) {
            Log.d(TAG, "No actions found in the notification")
            return null
        }

        Log.d(TAG, "Found ${actions.size} actions in notification")

        actions.forEachIndexed { index, action ->
            Log.d(TAG, "Action $index: '${action.title}'")
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                Log.d(TAG, "  Action $index has ${remoteInputs.size} remote input(s)")
                remoteInputs.forEachIndexed { riIndex, remoteInput ->
                    Log.d(TAG, "    RemoteInput $riIndex - ResultKey: '${remoteInput.resultKey}'")

                    // Check different possible result keys that WhatsApp might use
                    val resultKey = remoteInput.resultKey
                    if (resultKey.equals("remote_input", ignoreCase = true) ||
                        resultKey.equals("reply", ignoreCase = true) ||
                        resultKey.equals("key_text_reply", ignoreCase = true) ||
                        resultKey.contains("reply", ignoreCase = true) ||
                        resultKey.contains("input", ignoreCase = true)
                    ) {
                        Log.d(TAG, "    ✅ POTENTIAL MATCH: '$resultKey'")
                    }
                }
            } else {
                Log.d(TAG, "  Action $index has NO remote inputs")
            }
        }

        val foundAction = actions.find { action ->
            action.remoteInputs?.any { remoteInput ->
                val resultKey = remoteInput.resultKey
                // Try multiple possible result keys that WhatsApp might use
                resultKey.equals("remote_input", ignoreCase = true) ||
                        resultKey.equals("reply", ignoreCase = true) ||
                        resultKey.equals("key_text_reply", ignoreCase = true) ||
                        resultKey.contains("reply", ignoreCase = true) ||
                        resultKey.contains("input", ignoreCase = true)
            } ?: false
        }

        if (foundAction != null) {
            Log.d(TAG, "✅ Found WhatsApp reply action: '${foundAction.title}'")
        } else {
            Log.d(TAG, "❌ No matching WhatsApp reply action found")
            Log.d(
                TAG,
                "Tried matching: 'remote_input', 'reply', 'key_text_reply', or any containing 'reply'/'input'"
            )
        }

        return foundAction
    }

    private fun createReplyAction(
        replyText: String,
        whatsAppReplyAction: Notification.Action,
        notificationId: Int,
        contactName: String,
        ourUserName: String
    ): NotificationCompat.Action {
        Log.d(TAG, "createReplyAction: Creating action for '$replyText'")

        val originalRemoteInput = whatsAppReplyAction.remoteInputs?.get(0)
            ?: throw IllegalStateException("No remote input in WhatsApp action")

        val broadcastIntent = Intent(context, SmartReplyReceiver::class.java).apply {
            putExtra(SmartReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(
                SmartReplyReceiver.EXTRA_REPLY_PENDING_INTENT,
                whatsAppReplyAction.actionIntent
            )
            putExtra(SmartReplyReceiver.EXTRA_REMOTE_INPUT_KEY, originalRemoteInput.resultKey)

            // THIS IS THE KEY CHANGE: Pass the reply text as a simple extra
            putExtra("key_reply_text", replyText)
            putExtra("key_contact_name", contactName)
            putExtra("key_user_name", ourUserName)
        }

        val requestCode = replyText.hashCode()
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_foreground,
            replyText,
            replyPendingIntent
        ).build().also {
            Log.d(TAG, "✅ Reply action created successfully for: '$replyText'")
        }
    }
}
