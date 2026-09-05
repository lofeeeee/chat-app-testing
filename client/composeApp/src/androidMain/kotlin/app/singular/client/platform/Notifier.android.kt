package app.singular.client.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.singular.client.SingularApp

/**
 * Android notifications while the app is running.
 *
 * This is not feature 7. Push wakes a process that isn't there and needs FCM credentials the
 * project doesn't have; this posts a notification from a process that is already alive and
 * holding the socket. The two share a purpose and nothing else.
 *
 * Posting is gated on [NotificationManagerCompat.areNotificationsEnabled] rather than
 * attempted-and-caught: from API 33 the POST_NOTIFICATIONS permission is a runtime grant, and
 * asking for it belongs to a UI moment the user understands, not to the arrival of a message.
 * Until it is granted this reports unavailable and stays silent.
 */

private const val CHANNEL_ID = "singular.messages"

private val manager: NotificationManagerCompat? by lazy {
    runCatching {
        val context = SingularApp.appContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            channel.description = "New messages in your conversations"
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
        NotificationManagerCompat.from(context)
    }.getOrNull()
}

actual val notificationsAvailable: Boolean
    get() = runCatching { manager?.areNotificationsEnabled() == true }.getOrDefault(false)

actual fun showNotification(title: String, body: String) {
    // Swallowed for the same reason as the desktop actual: this runs inside the socket's
    // collect loop, and a missing permission must not take message delivery down with it.
    runCatching {
        val notifications = manager ?: return
        if (!notifications.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(SingularApp.appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Keyed on the title — one conversation replaces its own previous notice instead of
        // stacking ten separate toasts for ten messages from the same person.
        notifications.notify(title.hashCode(), notification)
    }
}
