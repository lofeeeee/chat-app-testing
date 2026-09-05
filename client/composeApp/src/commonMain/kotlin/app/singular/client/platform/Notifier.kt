package app.singular.client.platform

/**
 * System notifications, while the app is running.
 *
 * Deliberately *not* the same thing as feature 7's push notifications. Push is for a client
 * that isn't running — it needs FCM and APNs credentials the project doesn't have, and it is
 * delivered by the OS to a dead process. This is the other half: the app is open, a message
 * arrived in a conversation you aren't looking at, and something has to say so.
 *
 * Every platform implements it with whatever it already has, so this costs no dependency:
 * AWT's system tray on desktop, `NotificationManager` on Android. Where there is nothing
 * sensible to do (a browser tab with no permission granted), the actual is a no-op — a
 * notification that silently doesn't appear is far better than one that throws on a
 * background coroutine and takes the message subscription down with it.
 *
 * Top-level `expect fun` rather than an `expect object`: expect classes and objects are still
 * a Beta Kotlin feature and warn on every build. Same reasoning as [deviceId].
 */

/** True when this platform can actually show one, so the UI can say so honestly. */
expect val notificationsAvailable: Boolean

/**
 * Shows a notification. Never throws.
 *
 * Called from the notification socket's collect loop, where an exception would kill the
 * subscription and stop delivering the messages themselves — so every implementation swallows
 * whatever the platform throws at it.
 */
expect fun showNotification(title: String, body: String)
