package app.gomuks.android

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

internal const val SILENT_NOTIFICATION_CHANNEL_ID = "silent_notification"
internal const val NOISY_NOTIFICATION_CHANNEL_ID = "noisy_notification"
internal const val GROUP_NOTIFICATION_CHANNEL_ID = "group_notification"

fun createNotificationChannels(context: Context) {
    val notificationManager = NotificationManagerCompat.from(context)
    
    // Create silent channel (unchanged)
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            SILENT_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_silent))
            .setDescription(context.getString(R.string.notification_channel_silent))
            .setSound(null, null)
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // Create noisy channel with bright sound (for Direct Messages)
    val resId = R.raw.bright
    val soundUri = Uri.parse("android.resource://${context.packageName}/$resId")
    
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            NOISY_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH
        )
            .setName(context.getString(R.string.notification_channel_noisy))
            .setDescription(context.getString(R.string.notification_channel_noisy))
            .setSound(soundUri, null)
            .setVibrationPattern(longArrayOf(0, 500, 250, 500))  // Vibration pattern for noisy notifications
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)
            .build()
    )

    // Create group notification channel with descending sound (for Group Messages)
    val groupSoundUri = Uri.parse("android.resource://${context.packageName}/raw/descending")
    
    notificationManager.createNotificationChannel(
        NotificationChannelCompat.Builder(
            GROUP_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_group))
            .setDescription(context.getString(R.string.notification_channel_group))
            .setSound(groupSoundUri, null)
            .setVibrationPattern(longArrayOf(0, 1000, 500, 1000))  // Different vibration pattern for group notifications
            .setLightsEnabled(true)
            .setLightColor(R.color.primary_color)  // Different light color for group notifications
            .build()
    )
}
