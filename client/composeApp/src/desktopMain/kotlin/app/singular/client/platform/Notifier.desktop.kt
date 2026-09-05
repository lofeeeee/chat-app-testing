package app.singular.client.platform

import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Desktop notifications through AWT's system tray.
 *
 * The tray, and not a bespoke always-on-top window, because this is the mechanism the OS
 * already owns: Windows routes `displayMessage` into the real notification centre, so the
 * toast honours Focus Assist, appears in the notification history, and stacks with everything
 * else — none of which a hand-drawn window would do. It also costs no dependency, which
 * matters here: every library in this project is vendored offline.
 */

/**
 * Created once, lazily, and never removed.
 *
 * `SystemTray.add` is the expensive part and the icon has to stay registered for the lifetime
 * of the process — a tray icon removed after each toast makes Windows drop the notification it
 * was in the middle of showing.
 */
private val trayIcon: TrayIcon? by lazy {
    runCatching {
        if (!SystemTray.isSupported()) return@runCatching null
        val tray = SystemTray.getSystemTray()
        val size = tray.trayIconSize.width.coerceIn(16, 64)
        TrayIcon(appIcon(size), "Singular").also {
            it.isImageAutoSize = true
            tray.add(it)
        }
    }.getOrNull()
}

actual val notificationsAvailable: Boolean get() = trayIcon != null

actual fun showNotification(title: String, body: String) {
    // Swallowed deliberately. This is called from the notification socket's collect loop, and
    // a headless session or a desktop environment with no tray must not be able to kill
    // message delivery over a toast that couldn't be drawn.
    runCatching {
        trayIcon?.displayMessage(title, body, TrayIcon.MessageType.NONE)
    }
}

/**
 * The app mark: a filled circle with an S.
 *
 * Generated rather than shipped as a resource. A tray icon is scaled to whatever the OS asks
 * for, and a 16×16 PNG in the jar would look worse on a 200% display than a circle drawn at
 * the requested size. Legible at 16px is the constraint, which rules out any detail.
 */
private fun appIcon(size: Int): BufferedImage =
    BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
        val g = createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        g.color = Color(0x6D, 0x4A, 0xFF)
        g.fillOval(0, 0, size - 1, size - 1)
        g.color = Color.WHITE
        g.font = g.font.deriveFont(java.awt.Font.BOLD, size * 0.62f)
        val metrics = g.fontMetrics
        val glyph = "S"
        g.drawString(
            glyph,
            (size - metrics.stringWidth(glyph)) / 2f,
            (size - metrics.height) / 2f + metrics.ascent,
        )
        g.dispose()
    }
