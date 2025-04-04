package app.gomuks.android

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader.TileMode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

// For conversations API
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.app.ActivityOptions
import android.app.Service
import android.app.NotificationChannel
import android.os.IBinder
import android.graphics.drawable.Icon

class MessagingService : FirebaseMessagingService() {
    companion object {
        private const val LOGTAG = "Gomuks/MessagingService"
    }

    override fun onCreate() {
        super.onCreate()
        //logSharedPreferences()
    }

    override fun onNewToken(token: String) {
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString(getString(R.string.push_token_key), token)
            apply()
        }
        //logSharedPreferences()
        CoroutineScope(Dispatchers.IO).launch {
            tokenFlow.emit(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        //logSharedPreferences()
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
            data.imageAuth?.let { imageAuth ->
                // Pass roomName and roomAvatar from the message to showMessageNotification
                showMessageNotification(it, imageAuth, it.roomName, it.roomAvatar)
            }
        }
    }

    private fun pushUserToPerson(data: PushUser, msgpush: PushMessage, imageAuth: String, context: Context, callback: (Person) -> Unit) {
        // Retrieve the server URL from shared preferences
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), "")

        // Generate the full avatar URL for the sender
        val avatarURL = if (!serverURL.isNullOrEmpty() && !data.avatar.isNullOrEmpty()) {
            val baseURL = if (serverURL.endsWith("/") || data.avatar.startsWith("/")) {
                "$serverURL${data.avatar}"
            } else {
                "$serverURL/${data.avatar}"
            }
            "$baseURL?encrypted=false&image_auth=$imageAuth"
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

        if (avatarURL != null) {
            fetchAvatar(avatarURL, imageAuth, context) { circularBitmap ->
                if (circularBitmap != null) {
                    personBuilder.setIcon(IconCompat.createWithBitmap(circularBitmap))
                }
                // Build the Person object and invoke the callback
                callback(personBuilder.build())
            }
        } else {
            // Build the Person object and invoke the callback without an icon
            callback(personBuilder.build())
        }
    }



    // Modify the showMessageNotification function to use BigPictureStyle if the image field is present and not null
    private fun showMessageNotification(data: PushMessage, imageAuth: String, roomName: String?, roomAvatar: String?) {
        pushUserToPerson(data.sender, msgpush, imageAuth, this) { sender ->
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notifID = data.roomID.hashCode()

            val isGroupMessage = roomName != data.sender.name
	    if (isGroupMessage) { 
		    Log.i(LOGTAG, "This is a group message")
	    }

            // Adjust the text field based on reply or mention flags
            val adjustedText = when {
                data.reply -> "${data.sender.name} replied to you: ${data.text}" // Adjusted text for reply
                data.mention -> "${data.sender.name} mentioned you: ${data.text}" // Adjusted text for mention
                else -> data.text
            }
	    // Create a PendingIntent for the dismiss action
		val dismissIntent = Intent(this, NotificationDismissReceiver::class.java).apply {
		    putExtra("notification_id", notifID)
		}
		val dismissPendingIntent = PendingIntent.getBroadcast(this, notifID, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

            val messagingStyle = (manager.activeNotifications.lastOrNull { it.id == notifID }?.let {
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification)
            } ?: NotificationCompat.MessagingStyle(Person.Builder().setName("Self").build()))
                .setConversationTitle(if (isGroupMessage) roomName else null)
                .setGroupConversation(isGroupMessage) // Indicate it's a group conversation if applicable
                .addMessage(NotificationCompat.MessagingStyle.Message(adjustedText, data.timestamp, sender)) // Use adjustedText

            val channelID = if (isGroupMessage) {
                GROUP_NOTIFICATION_CHANNEL_ID
            } else {
                if (data.sound) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID
            }

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
	    if (isGroupMessage) {
		createOrUpdateGroupChatShortcut(this, data.roomID, roomName ?: data.roomName, msgpush.roomAvatar, msgpush.imageAuth)
	    } else {
	    	createOrUpdateChatShortcut(this, data.roomID, roomName ?: data.sender.name, sender)
	    }

            // Fetch the image if available
            if (!data.image.isNullOrEmpty()) {
                val imageUrl = buildImageUrl(data.image)
                fetchImageWithRetry(imageUrl, imageAuth) { bitmap ->
                    if (bitmap != null) {
                        Log.i(LOGTAG, "Using image in notification") // Log image usage

                        val bigPictureStyle = NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap) // Set the image bitmap
                            .bigLargeIcon(null as Bitmap?) // Explicitly pass null as Bitmap
                            .setSummaryText(adjustedText) // Set the summary text with adjustedText

                        val builder = NotificationCompat.Builder(this, channelID)
                            .setSmallIcon(R.drawable.matrix)
                            .setStyle(bigPictureStyle)
                            .setContentTitle(if (isGroupMessage) roomName else data.sender.name) // Set the content title
                            .setContentText(adjustedText) // Set the content text
                            .setWhen(data.timestamp)
                            .setAutoCancel(true)
                            .setContentIntent(pendingIntent)
                            .setShortcutId(data.roomID)  // Associate the notification with the conversation
                            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                            .setLargeIcon((sender.icon?.loadDrawable(this) as? BitmapDrawable)?.bitmap)  // Set the large icon with the sender's avatar
			    .addAction(R.drawable.ic_dismiss, "Dismiss", dismissPendingIntent) // Add dismiss action

                        with(NotificationManagerCompat.from(this@MessagingService)) {
                            if (ActivityCompat.checkSelfPermission(
                                    this@MessagingService,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                return@with
                            }
                            notify(notifID, builder.build())
                        }
                    } else {
                        // Fallback to the default behavior if the image couldn't be fetched
			Log.i(LOGTAG, "Fallback: Image could not be fetched")
                        showMessageNotificationWithoutImage(data, imageAuth, roomName, roomAvatar, sender, messagingStyle, channelID, pendingIntent, notifID)
                    }
                }
            } else {
                // Call a helper function to handle notifications without image
		Log.i(LOGTAG, "Sending notification without image")
                showMessageNotificationWithoutImage(data, imageAuth, roomName, roomAvatar, sender, messagingStyle, channelID, pendingIntent, notifID)
            }
        }
    }

    // Helper function to handle notifications without image
    private fun showMessageNotificationWithoutImage(
        data: PushMessage,
        imageAuth: String,
        roomName: String?,
        roomAvatar: String?,
        sender: Person,
        messagingStyle: NotificationCompat.MessagingStyle,
        channelID: String,
        pendingIntent: PendingIntent,
        notifID: Int
    ) {
        val largeIcon = (sender.icon?.loadDrawable(this) as? BitmapDrawable)?.bitmap

        Log.d(LOGTAG, "Large Icon Bitmap: $largeIcon")

	// Create a PendingIntent for the dismiss action
	val dismissIntent = Intent(this, NotificationDismissReceiver::class.java).apply {
	    putExtra("notification_id", notifID)
	}
	val dismissPendingIntent = PendingIntent.getBroadcast(this, notifID, dismissIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val builder = NotificationCompat.Builder(this, channelID)
            .setSmallIcon(R.drawable.matrix)
            .setStyle(messagingStyle)
            .setContentTitle(if (roomName != null) roomName else sender.name) // Set the content title
            .setContentText(data.text) // Set the content text
            .setWhen(data.timestamp)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setShortcutId(data.roomID)  // Associate the notification with the conversation
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setLargeIcon(largeIcon)  // Set the large icon with the sender's avatar
	    .addAction(R.drawable.ic_dismiss, "Dismiss", dismissPendingIntent) // Add dismiss action

        with(NotificationManagerCompat.from(this@MessagingService)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MessagingService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return@with
            }
            notify(notifID.hashCode(), builder.build())
        }
    }

    // For DMs
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

    // For Groups
    fun createOrUpdateGroupChatShortcut(context: Context, roomID: String, roomName: String?, roomAvatar: String?, imageAuth: String) {
    val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return

    val chatIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = "matrix:roomid/${roomID.substring(1)}".toUri()
    }
	
    val roomUrl = roomAvatar?.let { buildImageUrl(it) }

    // Retrieve the icon from the room avatar
    val icon = roomUrl?.let { fetchAvatar(it, imageAuth, context) }
    Log.d(LOGTAG, "Room Avatar Drawable: $icon")


    val shortcutBuilder = ShortcutInfo.Builder(context, roomID)
        .setShortLabel(roomName)
        .setLongLived(true)
        .setIntent(chatIntent)

    // Set the icon if it is available
    if (icon != null) {
        Log.d(LOGTAG, "Setting custom icon for group shortcut")
        shortcutBuilder.setIcon(icon)
    } else {
        Log.d(LOGTAG, "Setting default icon for group shortcut")
        shortcutBuilder.setIcon(Icon.createWithResource(context, R.drawable.ic_group_chat))
    }

    val shortcut = shortcutBuilder.build()

    shortcutManager.addDynamicShortcuts(listOf(shortcut))
}
	
	// Helper functions
	
    // Utility function to convert a bitmap to a circular bitmap
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint()
        val shader = BitmapShader(bitmap, TileMode.CLAMP, TileMode.CLAMP)
        paint.shader = shader
        paint.isAntiAlias = true

        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)

        return output
    }

    private fun logSharedPreferences() {
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val allEntries = sharedPref.all
        for ((key, value) in allEntries) {
            Log.d(LOGTAG, "SharedPreferences: $key = $value")
        }
    }
	
    // Add a new function to build the full URL for the image
    private fun buildImageUrl(imagePath: String): String {
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val serverURL = sharedPref.getString(getString(R.string.server_url_key), "")
        return if (!serverURL.isNullOrEmpty()) {
            if (serverURL.endsWith("/") || imagePath.startsWith("/")) {
                "$serverURL$imagePath"
            } else {
                "$serverURL/$imagePath"
            }
        } else {
            imagePath
        }
    }

    // Add a function to fetch the image with retry logic
    private fun fetchImageWithRetry(url: String, imageAuth: String, retries: Int = 3, callback: (Bitmap?) -> Unit) {
        var attempts = 0
        fun attemptFetch() {
            Log.d(LOGTAG, "Attempting to fetch image from URL: $url, Attempt: ${attempts + 1}") // Log attempt
            val glideUrl = GlideUrl(
                "$url&image_auth=$imageAuth",
                LazyHeaders.Builder() // Add the necessary headers and image_auth
                    .addHeader("Sec-Fetch-Site", "cross-site")
                    .addHeader("Sec-Fetch-Mode", "no-cors")
                    .addHeader("Sec-Fetch-Dest", "image")
                    .build()
            )
            Glide.with(this)
                .asBitmap()
                .load(glideUrl)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        Log.d(LOGTAG, "Image fetched successfully from URL: $url") // Log success
                        callback(resource)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        // Handle cleanup if necessary
                        callback(null)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (attempts < retries) {
                            attempts++
                            Log.d(LOGTAG, "Retrying to fetch image from URL: $url, Attempt: ${attempts + 1}") // Log retry
                            attemptFetch()
                        } else {
                            Log.e(LOGTAG, "Failed to fetch image from URL after $retries attempts: $url") // Log failure
                            callback(null)
                        }
                    }
                })
        }
        attemptFetch()
    }
	
	private fun getCacheFile(context: Context, url: String): File {
        val cacheDir = File(context.cacheDir, "avatar_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return File(cacheDir, url.hashCode().toString())
    }

    private fun isAvatarInCache(context: Context, url: String): Boolean {
        val cacheFile = getCacheFile(context, url)
        return cacheFile.exists()
    }

    private fun getAvatarFromCache(context: Context, url: String): Bitmap? {
        val cacheFile = getCacheFile(context, url)
        return if (cacheFile.exists()) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)
        } else {
            null
        }
    }

    private fun saveAvatarToCache(context: Context, url: String, bitmap: Bitmap) {
        val cacheFile = getCacheFile(context, url)
        FileOutputStream(cacheFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun fetchAvatar(url: String, imageAuth: String, context: Context, callback: (Bitmap?) -> Unit) {
        val cacheKey = url.split("?")[0] // Use URL without query parameters as cache key
        if (isAvatarInCache(context, cacheKey)) {
            Log.d(LOGTAG, "Avatar found in cache: $cacheKey")
            callback(getAvatarFromCache(context, cacheKey))
            return
        }

        val glideUrl = GlideUrl(
            "$url&image_auth=$imageAuth",
            LazyHeaders.Builder()
                .addHeader("Sec-Fetch-Site", "cross-site")
                .addHeader("Sec-Fetch-Mode", "no-cors")
                .addHeader("Sec-Fetch-Dest", "image")
                .build()
        )

        Glide.with(context)
            .asBitmap()
            .load(glideUrl)
            .error(R.drawable.ic_chat) // Add an error placeholder
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    // Convert the bitmap to a circular bitmap
                    val circularBitmap = getCircularBitmap(resource)
                    saveAvatarToCache(context, cacheKey, circularBitmap)
                    callback(circularBitmap)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // Handle cleanup if necessary
                    callback(null)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    Log.e(LOGTAG, "Failed to load image from URL: $url")
                    callback(null)
                }
            })
    }
}
