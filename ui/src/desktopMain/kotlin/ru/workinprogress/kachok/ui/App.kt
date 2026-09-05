package ru.workinprogress.kachok.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.metainfo.MagnetParser
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentRuntime
import ru.workinprogress.kachok.engine.runtime.TorrentSet
import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.main.MainWindow
import ru.workinprogress.kachok.ui.main.MainWindowState
import ru.workinprogress.kachok.ui.session.Lifecycle
import ru.workinprogress.kachok.ui.session.Preferences
import ru.workinprogress.kachok.ui.session.RateMeter
import ru.workinprogress.kachok.ui.session.Rates
import ru.workinprogress.kachok.ui.session.addFrom
import ru.workinprogress.kachok.ui.session.detailsOf
import ru.workinprogress.kachok.ui.session.rowOf
import ru.workinprogress.kachok.ui.session.settingsOf
import ru.workinprogress.kachok.ui.session.windowOf
import ru.workinprogress.kachok.ui.theme.KachokTheme
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The desktop surface, on the same engine the headless one runs.
 *
 * Not a second wiring: `TorrentSet` is the factory both use, so a change to how a session is built
 * reaches both clients or neither. What is here is what a window does with the result — sample
 * every session once a second, turn each into a row, draw them.
 *
 * A torrent may arrive on the command line, the way the CLI takes one; the rest arrive through the
 * toolbar. Both go through the same door.
 */
public fun main(args: Array<String>) {
    val torrent = args.firstOrNull()?.let { Path.of(it) }
    val directory = Path.of(args.getOrElse(1) { "." })
    application {
        // Closing the window asks every session to stop and waits for them, the way the CLI's
        // signal handler does — a download is not a thing to drop on the floor because a window
        // went away, and the design says as much: the row stays until the record is written.
        var closing by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = { closing = true },
            title = "kachok",
            state = rememberWindowState(size = DpSize(WINDOW_WIDTH, WINDOW_HEIGHT)),
        ) {
            KachokTheme {
                Client(torrent, directory, stopping = closing, onStopped = ::exitApplication)
            }
        }
    }
}

/**
 * A torrent that was recognised and is waiting for a yes.
 *
 * [metainfo] is null for a magnet: the window has no `MetadataFetcher` in front of a session yet,
 * so a magnet can be shown and not started
 * ([B-55](../../../../../../../docs/backlog/B-55-magnets-in-the-window.md)).
 */
private class Pending(
    val metainfo: Metainfo?,
    val shown: AddTorrentState,
)

/**
 * Every torrent this window is running, for as long as the window is.
 *
 * The engine's scope is a child of the effect's, so a composition that goes away takes the
 * sessions with it; the ordinary way out is [stopping], which is the clean stop rather than the
 * abrupt one.
 */
@Composable
internal fun Client(
    initial: Path?,
    directory: Path,
    stopping: Boolean = false,
    onStopped: () -> Unit = {},
) {
    var window by remember { mutableStateOf<MainWindowState?>(null) }
    var panelOpen by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(DetailsTab.Overview) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var selected by remember { mutableStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Read through a state, not captured: the effect is launched once and these change later, so
    // a plain read inside it would be the value from before the click.
    val askedToStop by rememberUpdatedState(stopping)
    val showPanel by rememberUpdatedState(panelOpen)
    val shownTab by rememberUpdatedState(tab)
    val shownAdd by rememberUpdatedState(pending?.shown)
    val chosen by rememberUpdatedState(selected)
    val showSettings by rememberUpdatedState(settingsOpen)
    // The dialog runs on the composition and the engine on its own dispatcher; a channel is the
    // seam, so a click never blocks a frame on a torrent being opened and hashed.
    val accepted = remember { Channel<Metainfo>(Channel.UNLIMITED) }

    LaunchedEffect(initial, directory) {
        val dispatchers = EngineDispatchers()
        val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob())
        val set = TorrentSet(dispatchers = dispatchers, scope = scope)
        val meters = mutableMapOf<String, RateMeter>()
        val savedTo = directory.toAbsolutePath().toString()
        try {
            initial?.let { open(set, MetainfoParser.parse(Files.readAllBytes(it)), directory, scope) }
            var asked = false
            var stopTicks = 0
            while (true) {
                while (true) {
                    val metainfo = accepted.tryReceive().getOrNull() ?: break
                    open(set, metainfo, directory, scope)
                }
                if (askedToStop && !asked) {
                    asked = true
                    set.torrents.forEach { it.stop() }
                }
                val running = set.torrents
                val lifecycle = if (asked) Lifecycle.Stopping else Lifecycle.Running
                val samples =
                    running.map { runtime ->
                        val state = runtime.state.value
                        state to meters.getOrPut(runtime.metainfo.name) { RateMeter() }.sample(state)
                    }
                val index = chosen.coerceIn(0, maxOf(0, running.lastIndex))
                window =
                    windowOf(
                        rows =
                            samples.mapIndexed { at, (state, rates) ->
                                rowOf(state, rates, lifecycle, selected = at == index)
                            },
                        // The status bar's two rates are the whole process's, which is what makes
                        // them different numbers from any one row's.
                        rates =
                            Rates(
                                down = samples.sumOf { it.second.down },
                                up = samples.sumOf { it.second.up },
                            ),
                        listenPort = set.listenPort,
                        dhtNodes = set.dhtPort?.let { samples.firstOrNull()?.first?.dhtNodes ?: 0 },
                        heapUsedBytes = heapUsed(),
                        heapMaxBytes = Runtime.getRuntime().maxMemory(),
                        // The banner names one session because one session failed; which one it is
                        // is the row that is tinted.
                        sessionError = samples.firstNotNullOfOrNull { it.first.sessionError },
                        details =
                            samples.getOrNull(index)?.takeIf { showPanel }?.let { (state, rates) ->
                                detailsOf(
                                    state = state,
                                    rates = rates,
                                    pieceLength = running[index].metainfo.pieceLength.toLong(),
                                    directory = savedTo,
                                    lifecycle = lifecycle,
                                    tab = shownTab,
                                )
                            },
                        adding = shownAdd,
                        settings = if (showSettings) settingsOf(Preferences(directory = savedTo)) else null,
                    )
                if (!asked) {
                    delay(TICK)
                } else if (allStopped(set) || ++stopTicks >= STOP_TICKS) {
                    break
                }
            }
        } finally {
            set.close()
            dispatchers.close()
            onStopped()
        }
    }
    window?.let {
        MainWindow(
            it,
            onAction = { action ->
                when (action.label) {
                    "Details panel" -> panelOpen = !panelOpen
                    "Settings" -> settingsOpen = !settingsOpen
                    "Add torrent" -> pending = chooseTorrent(directory)
                    "Paste magnet" -> pending = magnetFromClipboard(directory)
                    else -> Unit
                }
            },
            onTab = { chosenTab -> tab = chosenTab },
            onSelect = { row -> selected = row },
            onAddTorrent = { pending = chooseTorrent(directory) },
            onCancelAdd = { pending = null },
            onConfirmAdd = {
                pending?.metainfo?.let { accepted.trySend(it) }
                pending = null
            },
        )
    }
}

private suspend fun open(
    set: TorrentSet,
    metainfo: Metainfo,
    directory: Path,
    scope: CoroutineScope,
): TorrentRuntime =
    set.add(metainfo, RuntimeOptions(directory = directory)).also {
        it.restore()
        it.start(scope)
    }

/** Every session has answered its tracker, closed its peers, flushed and written its record. */
private suspend fun allStopped(set: TorrentSet): Boolean =
    withTimeoutOrNull(TICK) { set.torrents.forEach { it.awaitStopped() } } != null

/** The file chooser is the platform's, because a file chooser drawn by hand is always worse. */
private fun chooseTorrent(directory: Path): Pending? {
    val dialog = FileDialog(null as Frame?, "Add torrent", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".torrent") }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val path = Path.of(dialog.directory, file)
    val here = directory.toAbsolutePath().toString()
    return try {
        val metainfo = MetainfoParser.parse(Files.readAllBytes(path))
        Pending(metainfo, addFrom(metainfo, file, saveTo = here, defaultDirectory = here))
    } catch (unreadable: IOException) {
        System.err.println("kachok: cannot read $path: ${unreadable.message}")
        null
    } catch (malformed: IllegalArgumentException) {
        System.err.println("kachok: $file is not a usable torrent: ${malformed.message}")
        null
    }
}

/**
 * Reading the clipboard is not consent to download what is in it.
 *
 * The link is shown in the dialog and waits there; nothing is dialled until somebody says so — and
 * in this build nothing is dialled at all, because the window has no `MetadataFetcher` in front of
 * a session yet. The dialog says that where the file list would be.
 */
private fun magnetFromClipboard(directory: Path): Pending? {
    val text =
        try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (unavailable: UnsupportedFlavorException) {
            null
        } catch (unreadable: IOException) {
            null
        } ?: return null
    if (!text.trim().startsWith("magnet:")) return null
    val here = directory.toAbsolutePath().toString()
    return try {
        val shown = addFrom(MagnetParser.parse(text.trim()), saveTo = here, defaultDirectory = here)
        Pending(metainfo = null, shown = shown.refused(MAGNET_NOT_YET))
    } catch (malformed: IllegalArgumentException) {
        System.err.println("kachok: not a usable magnet link: ${malformed.message}")
        null
    }
}

private const val MAGNET_NOT_YET = "The window cannot fetch a magnet's metainfo yet."

private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/** The engine's own tick. Redrawing faster shows noise; slower makes the rate a lie. */
private val TICK = 1.seconds

/** Ten of them: the same ten seconds the headless client gives a clean stop before it goes. */
private const val STOP_TICKS = 10

private val WINDOW_WIDTH = 1200.dp

private val WINDOW_HEIGHT = 760.dp
