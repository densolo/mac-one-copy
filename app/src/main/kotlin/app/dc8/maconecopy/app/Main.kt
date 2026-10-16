package app.dc8.maconecopy.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import app.dc8.maconecopy.AppPaths
import app.dc8.maconecopy.Engine
import app.dc8.maconecopy.EngineException
import app.dc8.maconecopy.Settings
import app.dc8.maconecopy.expandPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.swing.JOptionPane
import kotlin.io.path.exists
import kotlin.system.exitProcess

/** `--data-dir`, `--name`, `--home` (or the MAC_ONE_COPY_* variables), like the CLI. */
private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        val (key, value) = if ("=" in a) a.substringBefore('=') to a.substringAfter('=') else a to args.getOrNull(++i)
        if (key in setOf("--data-dir", "--name", "--home") && value != null) out[key] = value
        else System.err.println("ignoring argument $a")
        i++
    }
    return out
}

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", "mac-one-copy")
    MacIntegration.setDockIcon()
    val opts = parseArgs(args)
    val paths = AppPaths.of(opts["--data-dir"] ?: System.getenv(AppPaths.ENV_DATA_DIR))
    var settings = Settings.load(paths)
    val name = opts["--name"] ?: System.getenv(AppPaths.ENV_NAME)
    val home = (opts["--home"] ?: System.getenv(AppPaths.ENV_HOME))?.let { expandPath(it).toString() }
    val updated = settings.copy(displayName = name ?: settings.displayName, homeDir = home ?: settings.homeDir)
    if (updated != settings || !paths.settingsFile.exists()) { settings = updated; settings.save(paths) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = Engine(paths, settings)
    try {
        engine.start(scope)
    } catch (e: EngineException) {
        JOptionPane.showMessageDialog(null, e.message, "mac-one-copy", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val model = AppModel(engine, scope)

    application(exitProcessOnExit = true) {
        fun quit() {
            scope.cancel()
            exitApplication()
        }
        LaunchedEffect(Unit) { MacIntegration.install(model, onQuit = ::quit) }
        val windowState = remember { WindowState(size = DpSize(1000.dp, 700.dp)) }
        val jobs by model.engine.jobs.collectAsState()
        Window(
            onCloseRequest = { model.windowVisible = false }, // keep receiving; the Dock icon brings it back
            visible = model.windowVisible,
            state = windowState,
            title = model.title(jobs),
            icon = MacIntegration.iconPainter,
        ) {
            AppTheme { App(model) }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
