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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.metainfo.MagnetParser
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentRuntime
import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.main.MainWindow
import ru.workinprogress.kachok.ui.main.MainWindowState
import ru.workinprogress.kachok.ui.session.Lifecycle
import ru.workinprogress.kachok.ui.session.RateMeter
import ru.workinprogress.kachok.ui.session.addFrom
import ru.workinprogress.kachok.ui.session.detailsOf
import ru.workinprogress.kachok.ui.session.rowOf
import ru.workinprogress.kachok.ui.session.windowOf
import ru.workinprogress.kachok.ui.theme.KachokTheme
import java.awt.FileDialog
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
 * Not a second wiring: `TorrentRuntime` is the factory both use, so a change to how a session is
 * built reaches both clients or neither. What is here is what a window does with the result —
 * sample it once a second, turn it into rows, draw them.
 *
 * **A torrent arrives on the command line, because there is no dialog yet.** Adding one is
 * [B-50](../../../../../../../docs/backlog/B-50-add-torrent.md); until then this takes the same
 * argument the CLI does, which keeps the two surfaces comparable while they are being compared.
 */
public fun main(args: Array<String>) {
    val torrent = args.firstOrNull()
    if (torrent == null) {
        System.err.println("usage: kachok-ui <file.torrent> [directory]")
        return
    }
    val directory = Path.of(args.getOrElse(1) { "." })
    application {
        // Closing the window asks the session to stop and waits for it, the way the CLI's signal
        // handler does — a download is not a thing to drop on the floor because a window went
        // away, and the design says as much: the row stays until the record is written.
        var closing by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = { closing = true },
            title = "kachok",
            state = rememberWindowState(size = DpSize(WINDOW_WIDTH, WINDOW_HEIGHT)),
        ) {
            KachokTheme {
                Torrent(Path.of(torrent), directory, stopping = closing, onStopped = ::exitApplication)
            }
        }
    }
}

/**
 * One torrent, opened and running for as long as this composable is on screen.
 *
 * The engine's scope is a child of the effect's, so a composition that goes away takes the session
 * with it; the ordinary way out is [stopping], which is the clean stop rather than the abrupt one.
 */
@Composable
internal fun Torrent(
    torrent: Path,
    directory: Path,
    stopping: Boolean = false,
    onStopped: () -> Unit = {},
) {
    var window by remember { mutableStateOf<MainWindowState?>(null) }
    // The panel and the tab are the window's, not the session's: they survive every sample, and
    // the toolbar's toggle reads them rather than keeping an opinion of its own.
    var panelOpen by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(DetailsTab.Overview) }
    var adding by remember { mutableStateOf<AddTorrentState?>(null) }
    val showPanel by rememberUpdatedState(panelOpen)
    val shownTab by rememberUpdatedState(tab)
    val pending by rememberUpdatedState(adding)
    // Read through a state, not captured: the effect is launched once and `stopping` becomes true
    // later, so a plain parameter read inside it would be the value from before the close.
    val askedToStop by rememberUpdatedState(stopping)
    LaunchedEffect(torrent, directory) {
        val dispatchers = EngineDispatchers()
        val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob())
        val metainfo = MetainfoParser.parse(Files.readAllBytes(torrent))
        val savedTo = directory.toAbsolutePath().toString()
        val runtime =
            TorrentRuntime.open(
                metainfo = metainfo,
                options = RuntimeOptions(directory = directory),
                dispatchers = dispatchers,
                scope = scope,
            )
        val meter = RateMeter()
        try {
            runtime.restore()
            val running = runtime.start(scope)
            var asked = false
            var stopTicks = 0
            while (true) {
                if (askedToStop && !asked) {
                    asked = true
                    runtime.stop()
                }
                val state = runtime.state.value
                val rates = meter.sample(state)
                window =
                    windowOf(
                        rows =
                            listOf(
                                rowOf(
                                    state,
                                    rates,
                                    if (asked) Lifecycle.Stopping else Lifecycle.Running,
                                    selected = true,
                                ),
                            ),
                        rates = rates,
                        listenPort = runtime.listenPort,
                        dhtNodes = runtime.dhtPort?.let { state.dhtNodes },
                        heapUsedBytes = heapUsed(),
                        heapMaxBytes = Runtime.getRuntime().maxMemory(),
                        sessionError = state.sessionError,
                        details =
                            if (showPanel) {
                                detailsOf(
                                    state = state,
                                    rates = rates,
                                    pieceLength = metainfo.pieceLength.toLong(),
                                    directory = savedTo,
                                    lifecycle = if (asked) Lifecycle.Stopping else Lifecycle.Running,
                                    tab = shownTab,
                                )
                            } else {
                                null
                            },
                        adding = pending,
                    )
                // The window stays up while the stop runs — the design's *stopping* row — and is
                // bounded the way the CLI bounds it: a peer that will not close must not be able
                // to hold a window open either.
                if (!asked) {
                    delay(TICK)
                } else if (withTimeoutOrNull(TICK) { running.join() } != null || ++stopTicks >= STOP_TICKS) {
                    break
                }
            }
        } finally {
            runtime.close()
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
                    "Add torrent" -> adding = chooseTorrent(directory)
                    "Paste magnet" -> adding = magnetFromClipboard(directory)
                    else -> Unit
                }
            },
            onTab = { chosen -> tab = chosen },
            onCancelAdd = { adding = null },
        )
    }
}

/**
 * The reason *Add* is greyed out.
 *
 * One `Session` per torrent is the engine's shape today, and nothing above it holds several — the
 * finding [B-52](../../../../../../../docs/backlog/B-52-ui-on-the-real-engine.md) opened with. So
 * the dialog recognises what was dropped, says everything it can about it, and admits it cannot
 * start it while another is running.
 */
private const val ONE_SESSION = "This build runs one torrent at a time."

/** The file chooser is the platform's, because a file chooser drawn by hand is always worse. */
private fun chooseTorrent(directory: Path): AddTorrentState? {
    val dialog = FileDialog(null as java.awt.Frame?, "Add torrent", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".torrent") }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val path = Path.of(dialog.directory, file)
    return try {
        addFrom(
            metainfo = MetainfoParser.parse(Files.readAllBytes(path)),
            fileName = file,
            saveTo = directory.toAbsolutePath().toString(),
            defaultDirectory = directory.toAbsolutePath().toString(),
        ).refused()
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
 * The link is shown in the dialog and waits there; nothing is dialled until somebody says so, and
 * in this build nothing is dialled at all.
 */
private fun magnetFromClipboard(directory: Path): AddTorrentState? {
    val text =
        try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (unavailable: UnsupportedFlavorException) {
            null
        } catch (unreadable: IOException) {
            null
        } ?: return null
    if (!text.trim().startsWith("magnet:")) return null
    return try {
        addFrom(
            link = MagnetParser.parse(text.trim()),
            saveTo = directory.toAbsolutePath().toString(),
            defaultDirectory = directory.toAbsolutePath().toString(),
        ).refused()
    } catch (malformed: IllegalArgumentException) {
        System.err.println("kachok: not a usable magnet link: ${malformed.message}")
        null
    }
}

private fun AddTorrentState.refused(): AddTorrentState =
    AddTorrentState(
        source = source,
        summary = summary,
        hash = hash,
        magnet = magnet,
        saveTo = saveTo,
        defaultNote = defaultNote,
        files = files,
        wantedSummary = wantedSummary,
        sequential = sequential,
        startImmediately = startImmediately,
        canAdd = false,
        whyNot = ONE_SESSION,
    )

private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/** The engine's own tick. Redrawing faster shows noise; slower makes the rate a lie. */
private val TICK = 1.seconds

/** Ten of them: the same ten seconds the headless client gives a clean stop before it goes. */
private const val STOP_TICKS = 10

private val WINDOW_WIDTH = 1200.dp

private val WINDOW_HEIGHT = 760.dp
