package app.dc8.maconecopy.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.Engine
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.JobView
import app.dc8.maconecopy.OfferDetails
import app.dc8.maconecopy.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

enum class Screen(val label: String) { Machines("Machines"), Files("Files"), Transfers("Transfers"), Settings("Settings"), Help("Help") }

/** UI state on top of the engine. Engine calls run on [scope]; errors end up in the snackbar. */
class AppModel(val engine: Engine, val scope: CoroutineScope) {
    var screen by mutableStateOf(Screen.Transfers)
    var windowVisible by mutableStateOf(true)
    var settings by mutableStateOf(engine.settings)
        private set
    val snackbar = SnackbarHostState()
    val folderSizes = FolderSizes(scope)

    /** Incoming offers that have not been answered or dismissed yet, oldest first. */
    val offerQueue = mutableStateListOf<String>()
    var openOffer by mutableStateOf<OfferDetails?>(null)
        private set
    private val seenOffers = HashSet<String>()

    /** Called when a new incoming offer shows up; set by [MacIntegration] to bounce the Dock icon. */
    var onNewOffer: () -> Unit = {}

    val needsSetup get() = settings.relayDir == null

    init {
        scope.launch(Dispatchers.Swing) {
            engine.jobs.collect { jobs ->
                val offers = jobs.filter { it.incomingOffer }.map { it.id }
                val fresh = offers.filter { seenOffers.add(it) }
                offerQueue.removeAll { it !in offers }
                offerQueue.addAll(fresh)
                if (fresh.isNotEmpty()) onNewOffer()
                if (openOffer != null && openOffer!!.job.id !in offers) openOffer = null
                showNextOffer()
            }
        }
    }

    /** Opens the accept dialog for the oldest waiting offer, once the window is visible. */
    fun showNextOffer() {
        if (openOffer != null || !windowVisible) return
        val id = offerQueue.firstOrNull() ?: return
        run { openOffer = engine.inspectOffer(id) }
    }

    fun decide(id: String, decision: Decision) = run {
        engine.decide(id, decision)
        closeOffer(id)
    }

    /** Hides the dialog; the offer stays in the Transfers list. */
    fun closeOffer(id: String) {
        offerQueue.remove(id)
        openOffer = null
        showNextOffer()
    }

    fun review(id: String) = run { openOffer = engine.inspectOffer(id) }

    fun updateSettings(transform: (Settings) -> Settings, then: () -> Unit = {}) = run {
        engine.updateSettings(transform)
        settings = engine.settings
        then()
    }

    fun show() {
        windowVisible = true
        showNextOffer()
    }

    /** Runs an engine call, showing failures in the snackbar. */
    fun run(action: suspend () -> Unit) = scope.launch(Dispatchers.Swing) {
        try {
            action()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            snackbar.showSnackbar(e.message ?: e.toString())
        }
    }

    /** `Mac (2) — mac-one-copy · 2 jobs · 34%`. */
    fun title(jobs: List<JobView>): String = "${settings.displayName} — mac-one-copy" + summary(jobs).let { if (it.isEmpty()) "" else " · $it" }

    companion object {
        fun summary(jobs: List<JobView>): String {
            val active = jobs.filter { it.state == JobState.Transferring || it.state == JobState.Paused }
            if (active.isEmpty()) return jobs.count { it.incomingOffer }.let { if (it > 0) "$it offer${if (it > 1) "s" else ""}" else "" }
            val total = active.sumOf { it.totalBytes }
            val pct = if (total == 0L) 0 else (active.sumOf { it.doneBytes } * 100 / total).toInt()
            return "${active.size} job${if (active.size > 1) "s" else ""} · $pct%"
        }
    }
}
