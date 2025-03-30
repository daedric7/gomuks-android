package app.gomuks.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

// For conversations API
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.app.ActivityOptions
import android.app.Service
import android.app.NotificationChannel
import android.os.IBinder

class MessagingService : FirebaseMessagingService() {
    companion object {
        private const val LOGTAG = "Gomuks/MessagingService"
    }

    override fun onNewToken(token: String) {
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString(getString(R.string.push_token_key), token)
            apply()
        }
        CoroutineScope(Dispatchers.IO).launch {
            tokenFlow.emit(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val pushEncKey = getExistingPushEncryptionKey(this)
        if (pushEncKey == null) {
            Log.e(LOGTAG, "No push encryption key found to handle $message")
            return
        }
        val decryptedPayload: String = try {
            Encryption.fromPlainKey(pushEncKey).decrypt(message.data.getValue("payload"))
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to decrypt $message", e)
            return
        }
        val data = try {
            Json.decodeFromString<PushData>(decryptedPayload)
        } catch (e: Exception) {
            Log.e(LOGTAG, "Failed to parse $decryptedPayload as JSON", e)
            return
        }
        Log.i(LOGTAG, "Decrypted payload: $data")
        if (!data.dismiss.isNullOrEmpty()) {
            with(NotificationManagerCompat.from(this)) {
                for (dismiss in data.dismiss) {
                    cancel(dismiss.roomID.hashCode())
                }
            }
        }
        data.messages?.forEach {
            showMessageNotification(it)
        }
    }

    private fun pushUserToPerson(data: PushUser, callback: (Person) -> Unit) {
        // Retrieve the server URL from shared preferences
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), "")

        // Generate the full avatar URL
        val avatarURL = if (!serverURL.isNullOrEmpty() && !data.avatar.isNullOrEmpty()) {
            if (serverURL.endsWith("/") || data.avatar.startsWith("/")) {
                "$serverURL${data.avatar}"
            } else {
                "$serverURL/${data.avatar}"
            }
        } else {
            null
        }

        // Log the entire content of data
        Log.d(LOGTAG, "PushUser data: $data")
        Log.d(LOGTAG, "Avatar URL: $avatarURL")

        // Continue building the Person object
        val personBuilder = Person.Builder()
            .setKey(data.id)
            .setName(data.name)
            .setUri("matrix:u/${data.id.substring(1)}")

        if (!avatarURL.isNullOrEmpty()) {
            Glide.with(this)
                .asBitmap()
                .load(avatarURL)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        personBuilder.setIcon(IconCompat.createWithBitmap(resource))
                        callback(personBuilder.build())
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        // Handle cleanup if necessary
                        callback(personBuilder.build())
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        super.onLoadFailed(errorDrawable)
                        callback(personBuilder.build())
                    }
                })
        } else {
            callback(personBuilder.build())
        }
    }

    private fun showMessageNotification(data: PushMessage) {
        pushUserToPerson(data.sender) { sender ->
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notifID = data.roomID.hashCode()

            val messagingStyle = (manager.activeNotifications.lastOrNull { it.id == notifID }?.let {
                MessagingStyle.extractMessagingStyleFromNotification(it.notification)
            } ?: MessagingStyle(pushUserToPerson(data.self)))
                .setConversationTitle(if (data.roomName != data.sender.name) data.roomName else null)
                .addMessage(MessagingStyle.Message(data.text, data.timestamp, sender))

            val channelID = if (data.sound) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID

            val deepLinkUri = "matrix:roomid/${data.roomID.substring(1)}/e/${data.eventID.substring(1)}".toUri()
            Log.i(LOGTAG, "Deep link URI: $deepLinkUri")

            val pendingIntent = PendingIntent.getActivity(
                this,
                notifID,
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    setData(deepLinkUri)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or 
                PendingIntent.FLAG_MUTABLE
            )

            // Create or update the conversation shortcut
            createOrUpdateChatShortcut(this, data.roomID, data.roomName ?: data.sender.name, sender)

            // Retrieve the bitmap for the sender's icon
            val senderIconBitmap = (sender.icon?.toIcon(context)?.loadDrawable(this) as? BitmapDrawable)?.bitmap
            Log.d(LOGTAG, "Sender Icon Bitmap: $senderIconBitmap")

            val builder = NotificationCompat.Builder(this, channelID)
                .setSmallIcon(R.drawable.matrix)
                .setStyle(messagingStyle)
                .setWhen(data.timestamp)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                //.setBubbleMetadata(bubbleMetadata)  // Add bubble metadata here
                .setShortcutId(data.roomID)  // Associate the notification with the conversation
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setLargeIcon(senderIconBitmap)  // Set the large icon

            with(NotificationManagerCompat.from(this@MessagingService)) {
                if (ActivityCompat.checkSelfPermission(
                        this@MessagingService,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@with
                }
                notify(notifID.hashCode(), builder.build())  // Notify with the bubble metadata included
            }
        }
    }

    fun createOrUpdateChatShortcut(context: Context, roomID: String, roomName: String, sender: Person) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return

        val chatIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = "matrix:roomid/${roomID.substring(1)}".toUri()
        }

        // Retrieve the icon from the sender
        val icon = sender.icon?.loadDrawable(context)?.let { drawable ->
            Log.d(LOGTAG, "Sender Icon Drawable: $drawable")
            Icon.createWithBitmap((drawable as BitmapDrawable).bitmap)
        }

        val shortcutBuilder = ShortcutInfo.Builder(context, roomID)
            .setShortLabel(roomName)
            .setLongLived(true)
            .setIntent(chatIntent)
            .setPerson(sender.toAndroidPerson()) // Convert to android.app.Person

        // Set the icon if it is available
        if (icon != null) {
            Log.d(LOGTAG, "Setting custom icon for shortcut")
            shortcutBuilder.setIcon(icon)
        } else {
            Log.d(LOGTAG, "Setting default icon for shortcut")
            shortcutBuilder.setIcon(Icon.createWithResource(context, R.drawable.ic_chat))
        }

        val shortcut = shortcutBuilder.build()

        shortcutManager.addDynamicShortcuts(listOf(shortcut))
    }
}
