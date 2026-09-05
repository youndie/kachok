package ru.workinprogress.kachok.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.appframe.AppFrame
import ru.workinprogress.appframe.TitleBarStyle
import ru.workinprogress.kachok.engine.io.EngineDispatchers
import ru.workinprogress.kachok.engine.metainfo.MagnetLink
import ru.workinprogress.kachok.engine.metainfo.MagnetParser
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.runtime.TorrentRuntime
import ru.workinprogress.kachok.engine.runtime.TorrentSet
import ru.workinprogress.kachok.engine.runtime.fetchMetainfo
import ru.workinprogress.kachok.ui.add.AddTorrentState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.main.MainWindow
import ru.workinprogress.kachok.ui.main.MainWindowState
import ru.workinprogress.kachok.ui.main.SortOrder
import ru.workinprogress.kachok.ui.main.ToolbarCommand
import ru.workinprogress.kachok.ui.session.Lifecycle
import ru.workinprogress.kachok.ui.session.Preferences
import ru.workinprogress.kachok.ui.session.RateMeter
import ru.workinprogress.kachok.ui.session.Rates
import ru.workinprogress.kachok.ui.session.Sample
import ru.workinprogress.kachok.ui.session.addFrom
import ru.workinprogress.kachok.ui.session.clicked
import ru.workinprogress.kachok.ui.session.detailsOf
import ru.workinprogress.kachok.ui.session.inOrder
import ru.workinprogress.kachok.ui.session.magnetRow
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
        // **The theme wraps the frame, not the frame's content.** `AppFrame` draws the title bar
        // itself, from `MaterialTheme.colorScheme.surfaceVariant` — with `KachokTheme` one level
        // lower the bar came out of the *default* light scheme while everything under it was dark.
        // Nothing caught it: the golden renders the same bar inside the theme, because a golden
        // cannot open a window, so it drew the right thing while the application drew the wrong one.
        KachokTheme {
            var closing by remember { mutableStateOf(false) }
            // The title bar is drawn, not the operating system's: the design draws it in its own
            // colours — `#161D1B`, a hairline under it, the name centred — which no OS chrome is
            // going to produce. AppFrame is the library for it, and the controls are still the
            // host's own, so this is a macOS window on macOS and a Windows one on Windows.
            //
            // 10 dp of padding and a 6 dp radius are the design's, measured off `main-window.png`;
            // everything else in `TitleBarStyle.MacOs` already was.
            AppFrame(
                onCloseRequest = { closing = true },
                title = "kachok",
                state = rememberWindowState(size = DpSize(WINDOW_WIDTH, WINDOW_HEIGHT)),
                style = KACHOK_TITLE_BAR,
            ) {
                Column(Modifier.fillMaxSize()) {
                    // The line under the title bar belongs to the content: the bar is a `Surface`
                    // with no border of its own, and every other bar in this window has one.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    Client(torrent, directory, stopping = closing, onStopped = ::exitApplication)
                }
            }
        }
    }
}

/**
 * Something that was recognised and is waiting for a yes: a torrent, or a magnet, never both.
 */
private class Pending(
    val metainfo: Metainfo?,
    val magnet: MagnetLink?,
    val shown: AddTorrentState,
)

/**
 * A magnet between the yes and the torrent.
 *
 * It is a row on the screen the whole time — the design's *Metadata* state, showing the info hash
 * where the name will be — because a magnet's fetch takes as long as the swarm takes and a window
 * that showed nothing for a minute would look broken rather than busy.
 */
private class Fetching(
    val link: MagnetLink,
    val started: Boolean = false,
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
    // The torrent that is selected, by info hash — not the row it is in. Sorting reorders the
    // rows under the selection, and an index would leave the highlight on a different torrent
    // than the one the person clicked.
    var selected by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf<List<String>>(emptyList()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(SortOrder()) }
    // Read through a state, not captured: the effect is launched once and these change later, so
    // a plain read inside it would be the value from before the click.
    val askedToStop by rememberUpdatedState(stopping)
    val showPanel by rememberUpdatedState(panelOpen)
    val shownTab by rememberUpdatedState(tab)
    val shownAdd by rememberUpdatedState(pending?.shown)
    val chosen by rememberUpdatedState(selected)
    val showSettings by rememberUpdatedState(settingsOpen)
    val sortedBy by rememberUpdatedState(sort)
    // The dialog runs on the composition and the engine on its own dispatcher; a channel is the
    // seam, so a click never blocks a frame on a torrent being opened and hashed.
    val accepted = remember { Channel<Pending>(Channel.UNLIMITED) }

    LaunchedEffect(initial, directory) {
        val dispatchers = EngineDispatchers()
        val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob())
        val set = TorrentSet(dispatchers = dispatchers, scope = scope)
        val meters = mutableMapOf<String, RateMeter>()
        val savedTo = directory.toAbsolutePath().toString()
        try {
            initial?.let { open(set, MetainfoParser.parse(Files.readAllBytes(it)), directory, scope) }
            val fetching = mutableListOf<Fetching>()
            var asked = false
            var stopTicks = 0
            while (true) {
                while (true) {
                    val next = accepted.tryReceive().getOrNull() ?: break
                    next.metainfo?.let { open(set, it, directory, scope) }
                    next.magnet?.let { fetching += Fetching(it) }
                }
                // A fetch runs on the engine's scope and puts its torrent through the same door a
                // file goes through, so there is one place a session is opened and not two.
                fetching.filter { !it.started }.forEach { pending ->
                    fetching[fetching.indexOf(pending)] = Fetching(pending.link, started = true)
                    scope.launch {
                        val metainfo =
                            try {
                                fetchMetainfo(pending.link, scope, dispatchers, set.listenPort)
                            } catch (unavailable: IllegalArgumentException) {
                                // The swarm had nothing to say. The row goes; a magnet nobody can
                                // answer is not a torrent, and there is no session to mark broken.
                                System.err.println("kachok: ${unavailable.message}")
                                null
                            }
                        fetching.removeAll { it.link === pending.link }
                        metainfo?.let {
                            accepted.trySend(Pending(it, null, addFrom(pending.link, savedTo, savedTo)))
                        }
                    }
                }
                if (askedToStop && !asked) {
                    asked = true
                    set.torrents.forEach { it.stop() }
                }
                val running = set.torrents
                val lifecycle = if (asked) Lifecycle.Stopping else Lifecycle.Running
                // Sorted here rather than in the composable: the details panel and the selection
                // both index into this list, and two orders would put the panel on another torrent
                // than the highlighted row.
                val ordered =
                    running
                        .map { runtime ->
                            val state = runtime.state.value
                            Sample(state, meters.getOrPut(runtime.metainfo.name) { RateMeter() }.sample(state))
                        }.inOrder(sortedBy)
                val byHash = running.associateBy { it.metainfo.infoHash.hex() }
                // A magnet's row comes first: it is the one the person just asked for, and the
                // one with the least to say about itself.
                val waiting = fetching.toList()
                // Every row on the screen, in the order it is drawn — magnets first, then the
                // sorted torrents. A click carries a row number and this is what turns it back
                // into a torrent.
                order = waiting.map { it.link.infoHash.hex() } + ordered.map { it.state.infoHash.hex() }
                // The selected torrent's row, or the first one when it has gone or none was
                // chosen. Never a stale number.
                val index = order.indexOf(chosen).coerceAtLeast(0)
                window =
                    windowOf(
                        rows =
                            waiting.mapIndexed { at, it -> magnetRow(it.link, selected = at == index) } +
                                ordered.mapIndexed { at, sample ->
                                    rowOf(
                                        sample.state,
                                        sample.rates,
                                        lifecycle,
                                        selected = waiting.size + at == index,
                                    )
                                },
                        // The status bar's two rates are the whole process's, which is what makes
                        // them different numbers from any one row's.
                        rates =
                            Rates(
                                down = ordered.sumOf { it.rates.down },
                                up = ordered.sumOf { it.rates.up },
                            ),
                        listenPort = set.listenPort,
                        dhtNodes = set.dhtPort?.let { ordered.firstOrNull()?.state?.dhtNodes ?: 0 },
                        heapUsedBytes = heapUsed(),
                        heapMaxBytes = Runtime.getRuntime().maxMemory(),
                        // The banner names one session because one session failed; which one it is
                        // is the row that is tinted.
                        sessionError = ordered.firstNotNullOfOrNull { it.state.sessionError },
                        details =
                            ordered.getOrNull(index - waiting.size)?.takeIf { showPanel }?.let { sample ->
                                detailsOf(
                                    state = sample.state,
                                    rates = sample.rates,
                                    pieceLength =
                                        byHash[sample.state.infoHash.hex()]
                                            ?.metainfo
                                            ?.pieceLength
                                            ?.toLong() ?: 0,
                                    directory = savedTo,
                                    lifecycle = lifecycle,
                                    tab = shownTab,
                                )
                            },
                        adding = shownAdd,
                        settings =
                            if (showSettings) {
                                settingsOf(
                                    Preferences(directory = savedTo, port = set.listenPort),
                                )
                            } else {
                                null
                            },
                        sort = sortedBy,
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
            // Exhaustive on purpose, and on the command rather than on the label: a control that
            // reports itself and nobody listens is what B-56 was.
            onAction = { action ->
                when (action.command) {
                    ToolbarCommand.ToggleDetails -> panelOpen = !panelOpen
                    ToolbarCommand.ToggleSettings -> settingsOpen = !settingsOpen
                    ToolbarCommand.AddTorrent -> pending = chooseTorrent(directory)
                    ToolbarCommand.PasteMagnet -> pending = magnetFromClipboard(directory)
                    null -> Unit
                }
            },
            onSort = { column -> sort = sort.clicked(column) },
            onTab = { chosenTab -> tab = chosenTab },
            onSelect = { row -> order.getOrNull(row)?.let { selected = it } },
            onAddTorrent = { pending = chooseTorrent(directory) },
            onCancelAdd = { pending = null },
            onConfirmAdd = {
                pending?.let { accepted.trySend(it) }
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
        Pending(metainfo, null, addFrom(metainfo, file, saveTo = here, defaultDirectory = here))
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
        val link = MagnetParser.parse(text.trim())
        Pending(metainfo = null, magnet = link, shown = addFrom(link, saveTo = here, defaultDirectory = here))
    } catch (malformed: IllegalArgumentException) {
        System.err.println("kachok: not a usable magnet link: ${malformed.message}")
        null
    }
}

private fun ru.workinprogress.kachok.engine.InfoHash.hex(): String =
    bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/** The engine's own tick. Redrawing faster shows noise; slower makes the rate a lie. */
private val TICK = 1.seconds

/** Ten of them: the same ten seconds the headless client gives a clean stop before it goes. */
private const val STOP_TICKS = 10

/**
 * The host's title bar with the design's two numbers on it.
 *
 * Shared with the golden, which draws the same `TitleBar` — one value, so the picture and the
 * window cannot disagree about the bar's geometry.
 */
internal val KACHOK_TITLE_BAR: TitleBarStyle =
    TitleBarStyle.forHost().copy(controlsPadding = 10.dp, cornerRadius = 6.dp)

private val WINDOW_WIDTH = 1200.dp

private val WINDOW_HEIGHT = 760.dp
