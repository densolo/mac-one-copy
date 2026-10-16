package app.dc8.maconecopy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import java.awt.desktop.QuitStrategy

/**
 * Dock behaviour instead of a tray icon: clicking the Dock icon reopens the window, Cmd+Q quits,
 * the badge counts incoming offers, a new offer bounces the icon, and the Dock menu shows progress.
 */
object MacIntegration {
    /** The app icon from resources (the .app bundle also gets it from its .icns). */
    val iconImage: BufferedImage? by lazy { MacIntegration::class.java.getResourceAsStream("/icon.png")?.use { ImageIO.read(it) } }
    val iconPainter: Painter? by lazy { iconImage?.let { BitmapPainter(it.toComposeImageBitmap()) } }

    /** Replaces the default Java Dock icon when running from IntelliJ or the jar; call before the first window. */
    fun setDockIcon() {
        val image = iconImage ?: return
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) runCatching { Taskbar.getTaskbar().iconImage = image }
    }

    fun install(model: AppModel, onQuit: () -> Unit) {
        val desktop = Desktop.getDesktop()
        runCatching { desktop.addAppEventListener(AppReopenedListener { model.show() }) }
        runCatching {
            desktop.setQuitStrategy(QuitStrategy.NORMAL_EXIT)
            desktop.setQuitHandler { _, response -> onQuit(); response.performQuit() }
        }
        val taskbar = if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar() else return
        model.onNewOffer = {
            if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) taskbar.requestUserAttention(true, false)
        }
        val status = MenuItem("").apply { isEnabled = false }
        val menu = PopupMenu().apply {
            add(status)
            addSeparator()
            add(MenuItem("Open").apply { addActionListener { model.scope.launch(Dispatchers.Swing) { model.show() } } })
        }
        if (taskbar.isSupported(Taskbar.Feature.MENU)) runCatching { taskbar.menu = menu }
        model.scope.launch(Dispatchers.Swing) {
            model.engine.jobs.collect { jobs ->
                val offers = jobs.count { it.incomingOffer }
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)) taskbar.setIconBadge(if (offers > 0) offers.toString() else null)
                status.label = AppModel.summary(jobs).ifEmpty { "No jobs" }
            }
        }
    }
}
