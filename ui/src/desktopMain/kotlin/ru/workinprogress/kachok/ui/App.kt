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
import ru.workinprogress.kachok.ui.remove.RemoveState
import ru.workinprogress.kachok.ui.session.Figures
import ru.workinprogress.kachok.ui.session.Lifecycle
import ru.workinprogress.kachok.ui.session.Preferences
import ru.workinprogress.kachok.ui.session.RateMeter
import ru.workinprogress.kachok.ui.session.Rates
import ru.workinprogress.kachok.ui.session.Sample
import ru.workinprogress.kachok.ui.session.addFrom
import ru.workinprogress.kachok.ui.session.chooseDirectory
import ru.workinprogress.kachok.ui.session.clicked
import ru.workinprogress.kachok.ui.session.detailsOf
import ru.workinprogress.kachok.ui.session.inOrder
import ru.workinprogress.kachok.ui.session.magnetRow
import ru.workinprogress.kachok.ui.session.matches
import ru.workinprogress.kachok.ui.session.rowOf
import ru.workinprogress.kachok.ui.session.settingsOf
import ru.workinprogress.kachok.ui.session.windowOf
import ru.workinprogress.kachok.ui.settings.SettingChange
import ru.workinprogress.kachok.ui.settings.SettingKey
import ru.workinprogress.kachok.ui.theme.KachokTheme
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
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
    // `~/Downloads` and not the working directory, which for an app launched from Finder or a
    // Start menu is wherever the launcher happened to be. It is also what the settings screen
    // prints as the default, and a default nothing uses is a lie printed on every row.
    val directory =
        args.getOrNull(1)?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "Downloads")
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
) {
    /** Where this one goes, which is a choice about this torrent and not about the next. */
    fun savingTo(path: String): Pending = Pending(metainfo, magnet, shown.savingTo(path))

    fun directory(): java.nio.file.Path =
        java.nio.file.Path
            .of(shown.saveTo)
}

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
    // **What the engine says, sampled once a second — and nothing else.**
    //
    // Everything the *person* decides — which panel is open, which column sorts, which row is
    // selected, what has been typed into settings — is read in composition, not folded into this.
    // It used to be: the whole window state was rebuilt inside the sampling loop, so every click
    // waited up to a second to appear and opening settings looked broken
    // ([B-64](../../../../../../../docs/backlog/B-64-a-click-waited-for-the-tick.md)).
    var engine by remember { mutableStateOf<EngineSnapshot?>(null) }

    var panelOpen by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(DetailsTab.Overview) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    // The torrent that is selected, by info hash — not the row it is in. Sorting reorders the rows
    // under the selection, and an index would leave the highlight on a different torrent than the
    // one the person clicked.
    var selected by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<RemoveState?>(null) }
    var filter by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    // What the settings screen has been told. Held for the session and not written anywhere: there
    // is no settings file yet, and inventing one is a decision about where it lives.
    var preferences by remember { mutableStateOf(Preferences(directory = directory.toAbsolutePath().toString())) }
    var sort by remember { mutableStateOf(SortOrder()) }
    // Read through a state, not captured: the effect is launched once and these change later, so
    // a plain read inside it would be the value from before the click.
    val askedToStop by rememberUpdatedState(stopping)
    val chosenPreferences by rememberUpdatedState(preferences)
    // The dialog runs on the composition and the engine on its own dispatcher; a channel is the
    // seam, so a click never blocks a frame on a torrent being opened and hashed.
    val accepted = remember { Channel<Pending>(Channel.UNLIMITED) }
    val dhtWanted = remember { Channel<Boolean>(Channel.CONFLATED) }
    val commanded = remember { Channel<TorrentCommand>(Channel.UNLIMITED) }

    LaunchedEffect(initial, directory) {
        val dispatchers = EngineDispatchers()
        val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob())
        val set = TorrentSet(dispatchers = dispatchers, scope = scope)
        val meters = mutableMapOf<String, RateMeter>()
        try {
            initial?.let {
                open(set, MetainfoParser.parse(Files.readAllBytes(it)), chosenPreferences, scope)
            }
            // Its own coroutine rather than a `tryReceive` in the loop below: that loop sleeps a
            // second between ticks, and a Pause that waited for it would be a button with a
            // second's lag on it — which is what B-64 was, in the one place it still applied.
            scope.launch {
                for (command in commanded) {
                    val runtime =
                        set.torrents.firstOrNull { it.metainfo.infoHash.hex() == command.infoHash }
                            ?: continue
                    when (command.kind) {
                        TorrentCommand.Kind.Pause -> {
                            runtime.pause()
                        }

                        TorrentCommand.Kind.Resume -> {
                            runtime.resume()
                        }

                        TorrentCommand.Kind.Recheck -> {
                            runtime.recheck()
                        }

                        TorrentCommand.Kind.Remove -> {
                            set.remove(runtime)
                        }

                        TorrentCommand.Kind.RemoveWithData -> {
                            // The paths are read *before* the remove: `remove` closes the files,
                            // and a `FileSet` that has been closed is not somewhere to ask what it
                            // was writing.
                            val paths = runtime.paths
                            set.remove(runtime)
                            deleteQuietly(paths)
                        }
                    }
                }
            }
            val fetching = mutableListOf<Fetching>()
            var asked = false
            var stopTicks = 0
            while (true) {
                dhtWanted.tryReceive().getOrNull()?.let { set.useDht(it) }
                while (true) {
                    val next = accepted.tryReceive().getOrNull() ?: break
                    // Where *this* torrent goes was decided in its own dialog; everything else
                    // about it comes from the settings.
                    next.metainfo?.let {
                        open(set, it, chosenPreferences.withDirectory(next.shown.saveTo), scope)
                    }
                    next.magnet?.let { fetching += Fetching(it) }
                }
                // A fetch runs on the engine's scope and puts its torrent through the same door a
                // file goes through, so there is one place a session is opened and not two.
                fetching.filter { !it.started }.forEach { waiting ->
                    fetching[fetching.indexOf(waiting)] = Fetching(waiting.link, started = true)
                    scope.launch {
                        val metainfo =
                            try {
                                fetchMetainfo(waiting.link, scope, dispatchers, set.listenPort)
                            } catch (unavailable: IllegalArgumentException) {
                                // The swarm had nothing to say. The row goes; a magnet nobody can
                                // answer is not a torrent, and there is no session to mark broken.
                                System.err.println("kachok: ${unavailable.message}")
                                null
                            }
                        fetching.removeAll { it.link === waiting.link }
                        metainfo?.let {
                            accepted.trySend(
                                Pending(
                                    it,
                                    null,
                                    addFrom(waiting.link, chosenPreferences.directory, chosenPreferences.directory),
                                ),
                            )
                        }
                    }
                }
                if (askedToStop && !asked) {
                    asked = true
                    set.torrents.forEach { it.stop() }
                }
                val running = set.torrents
                engine =
                    EngineSnapshot(
                        samples =
                            running.map { runtime ->
                                val state = runtime.state.value
                                Sample(state, meters.getOrPut(runtime.metainfo.name) { RateMeter() }.sample(state))
                            },
                        fetching = fetching.map { it.link },
                        pieceLengths =
                            running.associate {
                                it.metainfo.infoHash.hex() to it.metainfo.pieceLength.toLong()
                            },
                        listenPort = set.listenPort,
                        dhtNodes =
                            if (set.dhtEnabled) {
                                running
                                    .firstOrNull()
                                    ?.state
                                    ?.value
                                    ?.dhtNodes ?: 0
                            } else {
                                null
                            },
                        heapUsedBytes = heapUsed(),
                        heapMaxBytes = Runtime.getRuntime().maxMemory(),
                        lifecycle = if (asked) Lifecycle.Stopping else Lifecycle.Running,
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

    val snapshot = engine ?: return
    // Composed here rather than in the loop, so a click is a recomposition and not a wait.
    val ordered = snapshot.samples.inOrder(sort)
    // Built once, unselected, because which row is selected is decided *after* the filter has
    // decided which rows there are.
    val everyRow =
        snapshot.fetching.map { magnetRow(it) } +
            ordered.map { rowOf(it.state, it.rates, snapshot.lifecycle) }
    val everyKey = snapshot.fetching.map { it.infoHash.hex() } + ordered.map { it.state.infoHash.hex() }
    val kept = everyRow.indices.filter { everyRow[it].matches(filter) }
    val rowKeys = kept.map { everyKey[it] }
    val index = rowKeys.indexOf(selected).coerceAtLeast(0)
    val chosenSample =
        kept.getOrNull(index)?.let { source -> ordered.getOrNull(source - snapshot.fetching.size) }
    // The banner names a session, so *Show it* has to know which — the first one complaining, which
    // is also the row the list tints.
    val degraded = ordered.firstOrNull { it.state.sessionError != null }
    val window =
        windowOf(
            rows = kept.mapIndexed { at, source -> everyRow[source].copy(selected = at == index) },
            // The status bar counts every torrent, not the visible ones: a filter is a question
            // about the list, and a status line that answered it would be saying "3 torrents" to a
            // person who has sixteen.
            allRows = everyRow,
            filter = filter,
            // The status bar's two rates are the whole process's, which is what makes them
            // different numbers from any one row's.
            rates =
                Rates(
                    down = ordered.sumOf { it.rates.down },
                    up = ordered.sumOf { it.rates.up },
                ),
            listenPort = snapshot.listenPort,
            dhtNodes = snapshot.dhtNodes,
            heapUsedBytes = snapshot.heapUsedBytes,
            heapMaxBytes = snapshot.heapMaxBytes,
            // The banner names one session because one session failed; which one it is is the row
            // that is tinted.
            sessionError = degraded?.state?.sessionError,
            details =
                chosenSample?.takeIf { panelOpen }?.let { sample ->
                    detailsOf(
                        state = sample.state,
                        rates = sample.rates,
                        pieceLength = snapshot.pieceLengths[sample.state.infoHash.hex()] ?: 0,
                        directory = preferences.directory,
                        lifecycle = snapshot.lifecycle,
                        tab = tab,
                    )
                },
            adding = pending?.shown,
            removing = removing,
            settings = if (settingsOpen) settingsOf(preferences.boundTo(snapshot.listenPort)) else null,
            sort = sort,
        )
    MainWindow(
        window,
        // Exhaustive on purpose, and on the command rather than on the label: a control that
        // reports itself and nobody listens is what B-56 was.
        onAction = { action ->
            when (action.command) {
                ToolbarCommand.ToggleDetails -> {
                    panelOpen = !panelOpen
                }

                ToolbarCommand.ToggleSettings -> {
                    settingsOpen = !settingsOpen
                }

                ToolbarCommand.AddTorrent -> {
                    pending = chooseTorrent(preferences.directory)
                }

                ToolbarCommand.PasteMagnet -> {
                    pending = magnetFromClipboard(preferences.directory)
                }

                // `rowKeys[index]`, not `selected`: nothing selected means the first row is the
                // one drawn selected, and the button must act on the row a person can see is
                // highlighted rather than on nothing.
                ToolbarCommand.Pause -> {
                    rowKeys.getOrNull(index)?.let {
                        commanded.trySend(TorrentCommand(it, TorrentCommand.Kind.Pause))
                    }
                }

                ToolbarCommand.Recheck -> {
                    rowKeys.getOrNull(index)?.let {
                        commanded.trySend(TorrentCommand(it, TorrentCommand.Kind.Recheck))
                    }
                }

                ToolbarCommand.Remove -> {
                    // Built from the row that is highlighted, so the dialog names the torrent the
                    // person is looking at rather than one it went and found.
                    chosenSample?.let { sample ->
                        removing =
                            RemoveState(
                                name = sample.state.name,
                                where = preferences.directory,
                                howMuch = "${Figures.bytes(sample.state.downloaded)} on disk",
                            )
                    }
                }

                ToolbarCommand.Resume -> {
                    rowKeys.getOrNull(index)?.let {
                        commanded.trySend(TorrentCommand(it, TorrentCommand.Kind.Resume))
                    }
                }

                // Nothing to do, and said out loud: the `when` is exhaustive so that a command
                // added to the enum and forgotten here is a compile error.
                null -> {}
            }
        },
        onSort = { column -> sort = sort.clicked(column) },
        onFilter = { typed -> filter = typed },
        onTab = { chosenTab -> tab = chosenTab },
        onSelect = { row -> rowKeys.getOrNull(row)?.let { selected = it } },
        onAddTorrent = { pending = chooseTorrent(preferences.directory) },
        onBrowse = {
            chooseDirectory("Save to", preferences.directory)?.let { chosen ->
                pending = pending?.savingTo(chosen)
            }
        },
        onSetting = { change ->
            when (change) {
                is SettingChange.Browsed -> {
                    chooseDirectory("Save to", preferences.directory)?.let {
                        preferences = preferences.withDirectory(it)
                    }
                }

                is SettingChange.Toggled -> {
                    preferences = preferences.toggled(change.key, change.on)
                    // The one toggle that reaches further than the next torrent's options: joining
                    // the DHT opens a socket, so the set is told rather than a field.
                    if (change.key == SettingKey.Dht) dhtWanted.trySend(change.on)
                }

                is SettingChange.Typed -> {
                    preferences = preferences.typed(change.key, change.text)
                }
            }
        },
        onCopy = { text -> copyToClipboard(text) },
        onShowDegraded = {
            degraded?.let { sample ->
                selected = sample.state.infoHash.hex()
                panelOpen = true
                tab = DetailsTab.Overview
            }
        },
        onCancelRemove = { removing = null },
        onToggleRemoveData = { delete -> removing = removing?.withData(delete) },
        onConfirmRemove = {
            val delete = removing?.deleteData == true
            rowKeys.getOrNull(index)?.let {
                commanded.trySend(
                    TorrentCommand(
                        it,
                        if (delete) TorrentCommand.Kind.RemoveWithData else TorrentCommand.Kind.Remove,
                    ),
                )
            }
            removing = null
            // The row is going; the selection must not outlive it pointing at nothing.
            selected = null
        },
        onCancelAdd = { pending = null },
        onConfirmAdd = {
            pending?.let { accepted.trySend(it) }
            pending = null
        },
    )
}

/**
 * Delete what a removed torrent wrote, and its directory if that is now empty.
 *
 * Quietly, and one file at a time: a file the person moved, renamed or already deleted is not a
 * reason to leave the rest, and there is nothing useful to do about a permission error except say
 * so. The directory goes only if it is empty — deleting a *non*-empty one would take somebody
 * else's files with it.
 */
internal fun deleteQuietly(paths: List<Path>) {
    paths.forEach { path ->
        try {
            Files.deleteIfExists(path)
        } catch (refused: IOException) {
            System.err.println("kachok: cannot delete $path: ${refused.message}")
        }
    }
    paths.mapNotNull { it.parent }.distinct().forEach { directory ->
        try {
            Files.newDirectoryStream(directory).use { if (!it.iterator().hasNext()) Files.delete(directory) }
        } catch (refused: IOException) {
            System.err.println("kachok: leaving $directory: ${refused.message}")
        }
    }
}

/**
 * Something to do to one torrent, addressed by its info hash.
 *
 * By hash and not by index: the list is sorted by whatever column was last clicked, and a command
 * that travelled as "row 3" would arrive at whichever torrent row 3 had become.
 */
private class TorrentCommand(
    val infoHash: String,
    val kind: Kind,
) {
    enum class Kind { Pause, Resume, Recheck, Remove, RemoveWithData }
}

/**
 * One second's worth of what the engine says, and nothing the person decided.
 *
 * The split is the point: this is recomputed on a timer and everything else is recomputed on a
 * click.
 */
private class EngineSnapshot(
    val samples: List<Sample>,
    val fetching: List<MagnetLink>,
    val pieceLengths: Map<String, Long>,
    val listenPort: Int,
    val dhtNodes: Int?,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val lifecycle: Lifecycle,
)

private suspend fun open(
    set: TorrentSet,
    metainfo: Metainfo,
    preferences: Preferences,
    scope: CoroutineScope,
): TorrentRuntime =
    set.add(metainfo, preferences.runtimeOptions()).also {
        it.restore()
        it.start(scope)
    }

/** Every session has answered its tracker, closed its peers, flushed and written its record. */
private suspend fun allStopped(set: TorrentSet): Boolean =
    withTimeoutOrNull(TICK) { set.torrents.forEach { it.awaitStopped() } } != null

/** The file chooser is the platform's, because a file chooser drawn by hand is always worse. */
private fun chooseTorrent(directory: String): Pending? {
    val dialog = FileDialog(null as Frame?, "Add torrent", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".torrent") }
    dialog.directory = directory
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val path = Path.of(dialog.directory, file)
    val here = directory
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
 * The info hash, on the clipboard.
 *
 * Wrapped because `setContents` throws `IllegalStateException` when another process holds the
 * clipboard — a transient condition on every platform, and one that must not take the window down
 * over a copy button.
 */
private fun copyToClipboard(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    } catch (busy: IllegalStateException) {
        System.err.println("kachok: the clipboard is busy: ${busy.message}")
    }
}

/**
 * Reading the clipboard is not consent to download what is in it.
 *
 * The link is shown in the dialog and waits there; nothing is dialled until somebody says so — and
 * in this build nothing is dialled at all, because the window has no `MetadataFetcher` in front of
 * a session yet. The dialog says that where the file list would be.
 */
private fun magnetFromClipboard(directory: String): Pending? {
    val text =
        try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (unavailable: UnsupportedFlavorException) {
            null
        } catch (unreadable: IOException) {
            null
        } ?: return null
    if (!text.trim().startsWith("magnet:")) return null
    val here = directory
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
