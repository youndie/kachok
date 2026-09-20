package io.github.youndie.kachok.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import io.github.youndie.appframe.AppFrame
import io.github.youndie.appframe.TitleBarStyle
import io.github.youndie.kachok.control.SingleInstance
import io.github.youndie.kachok.control.configDirectory
import io.github.youndie.kachok.control.mcp.McpServer
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.MagnetLink
import io.github.youndie.kachok.engine.metainfo.MagnetParser
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentRuntime
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.runtime.fetchMetainfo
import io.github.youndie.kachok.engine.session.FilePriority
import io.github.youndie.kachok.engine.storage.FileSet
import io.github.youndie.kachok.ui.add.AddTorrentState
import io.github.youndie.kachok.ui.add.DroppedFiles
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.icons.appIcon
import io.github.youndie.kachok.ui.main.MainWindow
import io.github.youndie.kachok.ui.main.MainWindowState
import io.github.youndie.kachok.ui.main.SessionStatus
import io.github.youndie.kachok.ui.main.SortOrder
import io.github.youndie.kachok.ui.main.ToolbarCommand
import io.github.youndie.kachok.ui.remove.RemoveState
import io.github.youndie.kachok.ui.session.AUTOSTART_FLAG
import io.github.youndie.kachok.ui.session.ClientModel
import io.github.youndie.kachok.ui.session.Figures
import io.github.youndie.kachok.ui.session.Lifecycle
import io.github.youndie.kachok.ui.session.Preferences
import io.github.youndie.kachok.ui.session.Rates
import io.github.youndie.kachok.ui.session.Sample
import io.github.youndie.kachok.ui.session.StoredTorrent
import io.github.youndie.kachok.ui.session.addFrom
import io.github.youndie.kachok.ui.session.autostartFor
import io.github.youndie.kachok.ui.session.brokenRow
import io.github.youndie.kachok.ui.session.chooseDirectory
import io.github.youndie.kachok.ui.session.clicked
import io.github.youndie.kachok.ui.session.clientModelFor
import io.github.youndie.kachok.ui.session.detailsOf
import io.github.youndie.kachok.ui.session.forgetTorrent
import io.github.youndie.kachok.ui.session.inOrder
import io.github.youndie.kachok.ui.session.loadPreferences
import io.github.youndie.kachok.ui.session.loadStoredTorrents
import io.github.youndie.kachok.ui.session.magnetRow
import io.github.youndie.kachok.ui.session.matches
import io.github.youndie.kachok.ui.session.openFile
import io.github.youndie.kachok.ui.session.preferencesFile
import io.github.youndie.kachok.ui.session.ratesOf
import io.github.youndie.kachok.ui.session.rememberPaused
import io.github.youndie.kachok.ui.session.rememberPriorities
import io.github.youndie.kachok.ui.session.rememberSequential
import io.github.youndie.kachok.ui.session.rememberTorrent
import io.github.youndie.kachok.ui.session.rowOf
import io.github.youndie.kachok.ui.session.savePreferences
import io.github.youndie.kachok.ui.session.scaleTrayMenu
import io.github.youndie.kachok.ui.session.settingsOf
import io.github.youndie.kachok.ui.session.torrentsDirectory
import io.github.youndie.kachok.ui.session.trayTooltip
import io.github.youndie.kachok.ui.session.wideArguments
import io.github.youndie.kachok.ui.session.windowOf
import io.github.youndie.kachok.ui.settings.SettingChange
import io.github.youndie.kachok.ui.settings.SettingKey
import io.github.youndie.kachok.ui.theme.KachokTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
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
    // **Anything that goes wrong before the window has to leave a name behind.**
    //
    // A `jpackage` launcher answers an uncaught exception with a message box that says the Java
    // machine failed to start and nothing else — no class, no line, no cause. That is what somebody
    // opening a `.torrent` on Windows saw, and it is unanswerable: the launcher has already
    // swallowed the only sentence that would have said which of the twenty things this function
    // does was the one that threw. Everything up to `application {}` runs inside this, and the file
    // it writes sits beside the settings, where the person who hit it can find it.
    startupFailuresAreReadable {
        // **Before anything reads an argument**, because on Windows the ones this function was
        // handed may not be the ones the person typed: a name the machine's code page cannot spell
        // arrives as `?` and `Path.of` refuses it, which is a client that does not start at all
        // ([B-116](../../../../../../../docs/backlog/B-116-a-torrent-whose-name-is-not-ascii-cannot-be-opened-on-windows.md)).
        run(wideArguments(args))
    }
}

internal fun startupFailuresAreReadable(start: () -> Unit) {
    try {
        start()
    } catch (failed: Throwable) {
        val where = configDirectory().resolve("startup-error.txt")
        val text =
            buildString {
                // No timestamp: the file has one, written by the filesystem, and this repository's
                // own lint is right that a clock read here would be a clock nobody asked for.
                appendLine("kachok could not start")
                appendLine("os ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                appendLine("java ${System.getProperty("java.version")}")
                appendLine("launcher ${System.getProperty("jpackage.app-path") ?: "not a packaged build"}")
                appendLine()
                appendLine(failed.stackTraceToString())
            }
        // stderr as well as the file: a build run from a terminal should not need somebody to go
        // looking, and the file is for the one launched from a file manager, which has no terminal.
        System.err.print(text)
        try {
            java.nio.file.Files
                .createDirectories(where.parent)
            java.nio.file.Files
                .writeString(where, text)
            System.err.println("kachok: written to $where")
        } catch (unwritable: java.io.IOException) {
            System.err.println("kachok: and $where could not be written either: ${unwritable.message}")
        }
        // Rethrown, so the exit code still says it failed. What changes is that there is now
        // somewhere to read *why*.
        throw failed
    }
}

private fun run(args: Array<String>) {
    // **The only way to ask the shipped artifact anything.** `jlink` strips the launchers, so the
    // packaged image has no `java` to run a check with — this launcher is the one executable in it.
    // See `Preflight.kt` and B-78; it exits before anything opens a window.
    if (args.firstOrNull() == "--preflight") {
        exitProcess(preflight(args.getOrNull(1)?.let { Path.of(it) }))
    }
    // Flags out of the way first. Before this, `kachok --autostart` would have taken its own flag
    // for a torrent path and put an unreadable file on the screen at every login.
    val flags = args.filter { it.startsWith("--") }.toSet()
    val given = args.filterNot { it.startsWith("--") }
    val torrent = given.firstOrNull()?.let { Path.of(it) }
    // **One client per machine, and this launch may not be it.** A `.torrent` double-clicked in a
    // file manager starts a new process on all three platforms; if one is already running, its
    // path goes there and this one exits. Two clients would be two listeners on one port and two
    // writers in one download directory, and no `TorrentSet` can see across a process boundary
    // to refuse that (B-84).
    val instance = SingleInstance.claim(configDirectory(), listOfNotNull(torrent))
    if (instance == null) return
    // A shutdown hook and not `onStopped`, because the lock has to go however this process ends —
    // a stale file is not fatal (the next launch takes it over) but it costs that launch a
    // connection attempt, and a hook covers the paths a `finally` does not.
    Runtime.getRuntime().addShutdownHook(Thread(instance::close))
    // **macOS does not put a double-clicked document in `argv`.** It sends an Apple Event, which
    // reaches Java here — so the code that works on Windows and Linux receives nothing on the one
    // platform whose association was easiest to get right. Same channel, so the window has one
    // door for a torrent that arrives from outside it.
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
        Desktop.getDesktop().setOpenFileHandler { event ->
            event.files.forEach { instance.opened.trySend(it.toPath()) }
        }
    }
    // `~/Downloads` and not the working directory, which for an app launched from Finder or a
    // Start menu is wherever the launcher happened to be. It is also what the settings screen
    // prints as the default, and a default nothing uses is a lie printed on every row.
    val directory =
        given.getOrNull(1)?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "Downloads")
    application {
        // **The theme wraps the frame, not the frame's content.** `AppFrame` draws the title bar
        // itself, from `MaterialTheme.colorScheme.surfaceVariant` — with `KachokTheme` one level
        // lower the bar came out of the *default* light scheme while everything under it was dark.
        // Nothing caught it: the golden renders the same bar inside the theme, because a golden
        // cannot open a window, so it drew the right thing while the application drew the wrong one.
        // **The tray, and the two facts that decide whether there is one.**
        //
        // `isTraySupported` is false on a desktop that dropped the status-icon protocol — GNOME did
        // — and a client that closes into a tray that does not exist is one somebody has to kill
        // from a terminal. So it is read once, here, and everything below asks it rather than
        // assuming: with no tray the close button does what it always did (B-88).
        // **The window's state and its engine, built one level above the window.**
        //
        // `application` outlives every window inside it, so a holder remembered here survives one
        // being thrown away and rebuilt — which is the whole of B-79 and is what makes the torrents
        // something other than a property of a composition. On this desktop nothing throws the
        // window away today; on Android a rotation does, and this is the seam that will hold then.
        val model = remember { clientModelFor(directory, preferencesFile(), directoryOverrides = given.size > 1) }
        val hasTray = remember { isTraySupported }
        // *Quit* from the tray means stop for real, so the close path below must not send the
        // window back into the tray it was just quit from.
        var quitting by remember { mutableStateOf(false) }
        // Mirrored up from `Client`, which owns the settings file; the tray is above the composition
        // that reads it. A callback rather than a second read of the file: two readers of one
        // setting is two answers whenever somebody changes it.
        var closeToTray by remember { mutableStateOf(true) }
        // The tray's tooltip, mirrored up from the window's own status bar so the two cannot
        // disagree about a rate.
        var status by remember { mutableStateOf<SessionStatus?>(null) }
        var explainTray by remember { mutableStateOf(false) }
        // Hidden rather than minimised when the system started it *and* there is a tray to come
        // back from — which is the case B-83 could not have and had to settle for minimised.
        var windowVisible by remember { mutableStateOf(!(AUTOSTART_FLAG in flags && hasTray)) }
        val trayState = rememberTrayState()

        if (hasTray) {
            Tray(
                icon = appIcon,
                state = trayState,
                tooltip = trayTooltip(status),
                // Double-clicking the icon is what a person tries first, before finding a menu.
                onAction = { windowVisible = true },
            ) {
                Item("Show kachok", onClick = { windowVisible = true })
                Item("Quit", onClick = {
                    windowVisible = true
                    quitting = true
                })
            }
        }

        // AWT draws the tray's menu itself, at a font size nothing in this application chose. Once,
        // after the tray exists — see `scaleTrayMenu` and B-91.
        LaunchedEffect(hasTray) { if (hasTray) scaleTrayMenu() }

        // One notice, the first time a window goes into the tray. The alternative failure is
        // somebody pressing close, seeing nothing, and pressing it again.
        LaunchedEffect(explainTray) {
            if (!explainTray) return@LaunchedEffect
            trayState.sendNotification(
                Notification(
                    "kachok is still running",
                    "Its icon is in the tray. Quit from there.",
                    Notification.Type.Info,
                ),
            )
        }

        KachokTheme {
            var closing by remember { mutableStateOf(quitting) }
            LaunchedEffect(quitting) { if (quitting) closing = true }
            // The title bar is drawn, not the operating system's: the design draws it in its own
            // colours — `#161D1B`, a hairline under it, the name centred — which no OS chrome is
            // going to produce. AppFrame is the library for it, and the controls are still the
            // host's own, so this is a macOS window on macOS and a Windows one on Windows.
            //
            // 10 dp of padding and a 6 dp radius are the design's, measured off `main-window.png`;
            // everything else in `TitleBarStyle.MacOs` already was.
            // The two keys the empty state advertises, and the two the design's own map names.
            //
            // **`onKeyEvent`, deliberately, and never `onPreviewKeyEvent`.** Preview runs top-down
            // and would take Cmd+V out of the filter field and every number in the settings before
            // they saw it; this runs after the focused component has had its turn, so a text field
            // that handles the press keeps it and an unfocused window gets it here.
            var shortcut by remember { mutableStateOf<Shortcut?>(null) }
            AppFrame(
                // **Close means "leave it running", when there is somewhere for it to run.** A
                // torrent client whose close button stops every transfer is one that has to be
                // left open to do its job. With no tray, or with the setting off, it still stops:
                // vanishing into a tray that is not there would be worse than stopping.
                onCloseRequest = {
                    if (hasTray && closeToTray && !quitting) {
                        windowVisible = false
                        explainTray = true
                    } else {
                        closing = true
                    }
                },
                visible = windowVisible,
                title = "kachok",
                // The same drawing the installer puts on the desktop, so a window in the dock or
                // the taskbar is the application somebody launched, not a Java coffee cup.
                icon = appIcon,
                // **Out of the way when the system started it.** A login should not be
                // interrupted by a window nobody asked for at that moment. Minimised where there is
                // no tray, and hidden entirely where there is — which is what B-83 wanted and could
                // not have until this item gave the window somewhere to come back from.
                state =
                    rememberWindowState(
                        size = DpSize(WINDOW_WIDTH, WINDOW_HEIGHT),
                        isMinimized = AUTOSTART_FLAG in flags && !hasTray,
                    ),
                style = KACHOK_TITLE_BAR,
                onKeyEvent = { event ->
                    // A new object each time, so pressing the same keys twice is two requests
                    // rather than one the effect below cannot tell from the first.
                    shortcutFor(
                        event.type,
                        event.key,
                        modified = event.isMetaPressed || event.isCtrlPressed,
                    )?.let { shortcut = Shortcut(it) } != null
                },
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
                    Client(
                        torrent,
                        directory,
                        model = model,
                        opened = instance.opened,
                        agents = { instance.agents = it },
                        stopping = closing,
                        onStopped = ::exitApplication,
                        shortcut = shortcut,
                        directoryOverrides = given.size > 1,
                        onPreferences = { closeToTray = it.closeToTray && hasTray },
                        // Only while there is a tray to put it on: computing a tooltip nothing
                        // draws is work done once a second for nobody.
                        onStatus = if (hasTray) ({ status = it }) else ({}),
                        trayProblem =
                            if (hasTray) {
                                null
                            } else {
                                "This desktop has no tray, so the close button stops the torrents."
                            },
                        explainedTray = explainTray,
                    )
                }
            }
        }
    }
}

/**
 * Something that was recognised and is waiting for a yes: a torrent, or a magnet, never both.
 */
internal class Pending(
    val metainfo: Metainfo?,
    val magnet: MagnetLink?,
    val shown: AddTorrentState,
) {
    /** Where this one goes, which is a choice about this torrent and not about the next. */
    fun savingTo(path: String): Pending = Pending(metainfo, magnet, shown.savingTo(path))

    /** In order rather than rarest first, decided in this torrent's own dialog. */
    fun sequentially(on: Boolean): Pending = Pending(metainfo, magnet, shown.sequentially(on))

    /** One file ticked or unticked in the dialog, before anything has been opened. */
    fun withFile(
        index: Int,
        wanted: Boolean,
    ): Pending = Pending(metainfo, magnet, shown.withFile(index, wanted))

    /**
     * The files this torrent will not fetch, by index.
     *
     * Read off the dialog at the moment *Add* is pressed, because that is when the decision is
     * final: the picker is told once and cannot be told again.
     */
    fun unwanted(): Set<Int> =
        shown.files
            .mapIndexedNotNull { at, file -> at.takeIf { !file.wanted } }
            .toSet()

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
internal class Fetching(
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
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Client(
    initial: Path?,
    directory: Path,
    stopping: Boolean = false,
    onStopped: () -> Unit = {},
    /** The last key press the window turned into a request, or null. */
    shortcut: Shortcut? = null,
    /**
     * Where the settings live.
     *
     * A parameter and not a constant, because otherwise this window reads the machine's real
     * settings file wherever it runs — which a test found by downloading into the developer's own
     * `~/Downloads` and joining the DHT, because that is what the file on that machine said.
     */
    settingsFile: Path = preferencesFile(),
    /**
     * Where the remembered torrents live, for the same reason [settingsFile] is a parameter: a test
     * that used the real one would open whatever this developer happens to be downloading.
     */
    torrents: Path = torrentsDirectory(),
    /**
     * Torrents handed over by a *later* launch of this application.
     *
     * Double-clicking a `.torrent` starts a new process on every platform; that process finds this
     * one, gives it the path and exits ([SingleInstance]), and the path arrives here. On macOS the
     * same channel carries the Apple Event, because a document opened there never reaches `argv`
     * at all ([B-84](../../../../../../../docs/backlog/B-84-torrent-files-open-with-the-client.md)).
     */
    opened: ReceiveChannel<Path>? = null,
    /**
     * Where an agent's MCP sessions are registered, once this window has an engine to give them
     * ([B-117](../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md)).
     *
     * A parameter and not a reach for the lock, for the same reason [settingsFile] is one: a test
     * that registered itself on the real socket would hand whatever agent is running on this
     * machine a window that is about to be torn down. Null in a test, and in a window that could
     * not bind the lock at all.
     */
    agents: ((SingleInstance.McpSessions?) -> Unit)? = null,
    /**
     * What the settings say, reported up as they change.
     *
     * The tray lives above this composition and needs one of them; a second read of the settings
     * file up there would be a second answer whenever somebody changes it.
     */
    onPreferences: (Preferences) -> Unit = {},
    /**
     * The status bar's figures, for whatever is drawn outside this composition.
     *
     * The tray's tooltip is the only reader today, and it takes the *same* strings the bar draws
     * rather than computing rates of its own — two implementations of one number is how they come
     * to disagree.
     */
    onStatus: (SessionStatus) -> Unit = {},
    /** Why this desktop has no tray, or null when it has one. Drawn on the row it disables. */
    trayProblem: String? = null,
    /** The tray has explained itself once; the settings file remembers so the next run does not. */
    explainedTray: Boolean = false,
    /**
     * A directory named on the command line beats the stored one, for this run only.
     *
     * Without it the file wins and `kachok x.torrent /srv/here` quietly ignores its second
     * argument; with it always on, the stored directory could never take effect.
     */
    directoryOverrides: Boolean = false,
    /**
     * Everything this window is, held where a window is not
     * ([B-79](../../../../../../../docs/backlog/B-79-the-windows-state-outlives-its-composition.md)).
     *
     * A parameter with a default rather than a `remember` in the body, and the default is what the
     * body used to do: read the settings file, ask the system about autostart, and start from
     * there. A caller that has a holder — the application, which outlives this window — passes one
     * and its torrents and its selection survive the composition being thrown away; a caller that
     * does not, which is every test of the window's own behaviour, gets the lifetime it had before.
     */
    model: ClientModel =
        remember {
            clientModelFor(directory, settingsFile, directoryOverrides).also { it.ownedByTheWindow = true }
        },
) {
    // **What the engine says, sampled once a second — and nothing else.**
    //
    // Everything the *person* decides — which panel is open, which column sorts, which row is
    // selected, what has been typed into settings — is read in composition, not folded into this.
    // It used to be: the whole window state was rebuilt inside the sampling loop, so every click
    // waited up to a second to appear and opening settings looked broken
    // ([B-64](../../../../../../../docs/backlog/B-64-a-click-waited-for-the-tick.md)).
    var engine by model.engine

    var panelOpen by model.panelOpen
    var tab by model.tab
    var pending by model.pending
    // The torrent that is selected, by info hash — not the row it is in. Sorting reorders the rows
    // under the selection, and an index would leave the highlight on a different torrent than the
    // one the person clicked.
    var selected by model.selected
    var removing by model.removing
    var filter by model.filter
    var dropping by model.dropping
    var clipboardMagnet by model.clipboardMagnet
    // The last magnet this window offered, so returning to it ten times does not offer the same one
    // ten times. Cleared by dismissing, which is a person saying no to *this* link.
    var offeredMagnet by model.offeredMagnet

    // Remembered torrents the client could not open. Not a session and not a magnet, so it is not
    // in `EngineSnapshot`; it is decided once, when the list is read, and never changes after.
    var broken by model.broken
    // Asked of the system rather than of the file: somebody can remove a launch agent or a Run key
    // without this client, and a checkbox that reports the settings file would then be wrong in the
    // one direction that matters — claiming the client starts with the computer when it does not.
    val autostart = remember { autostartFor() }
    var autostartProblem by model.autostartProblem
    // Where the last torrent actually went, back from the engine loop to the composition that owns
    // the preferences. Conflated: only the most recent one is the answer.
    val saved = model.saved
    var pendingDrop by model.pendingDrop
    var settingsOpen by model.settingsOpen
    // What the settings screen has been told. Held for the session and not written anywhere: there
    // is no settings file yet, and inventing one is a decision about where it lives.
    // Read once, at the start, and not on every recomposition: the file is the previous run's
    // answer, and this run's answer is the state below it.
    // Read when the holder was built, not on every recomposition: the file is the previous run's
    // answer, and this run's answer is the state it seeded.
    var preferences by model.preferences

    // Written back after half a second of quiet. `LaunchedEffect` cancels the previous one when the
    // key changes, so typing `1200` into a rate limit is one write and not four — and the delay is
    // short enough that closing the window straight after a change still lands it.
    LaunchedEffect(preferences) {
        delay(SETTINGS_SETTLE)
        savePreferences(settingsFile, preferences)
    }

    // A torrent from somewhere other than this window: a second launch, or a double-click on
    // macOS. It goes through `pendingDrop`, which is the same door a dropped file uses — one place
    // where a path becomes the add dialog, and not three that have to agree with each other.
    LaunchedEffect(opened) {
        val channel = opened ?: return@LaunchedEffect
        for (path in channel) {
            // Waits for the previous one to be answered rather than overwriting it: three
            // double-clicks in a second are three dialogs in turn, not the last one.
            snapshotFlow { pendingDrop == null && pending == null }.first { it }
            pendingDrop = path
        }
    }

    LaunchedEffect(preferences) { onPreferences(preferences) }

    // Written down once, so a second run does not explain the tray to somebody who has seen it.
    LaunchedEffect(explainedTray) {
        if (explainedTray && !preferences.trayExplained) preferences = preferences.copy(trayExplained = true)
    }

    LaunchedEffect(saved) {
        for (directory in saved) {
            if (directory != preferences.lastDirectory) preferences = preferences.copy(lastDirectory = directory)
        }
    }

    // A dropped file, read here rather than in the drop callback: that runs while a composition is
    // already in flight, and this effect has `preferences` to hand.
    LaunchedEffect(pendingDrop) {
        pendingDrop?.let { path ->
            pending = torrentAt(path, preferences.addFrom)
            pendingDrop = null
        }
    }

    // Keyed on the object and not on the enum: pressing Cmd+O twice is two requests, and an effect
    // keyed on `OpenFile` would run once.
    LaunchedEffect(shortcut) {
        when (shortcut?.what) {
            null -> Unit
            Shortcut.Kind.OpenFile -> pending = chooseTorrent(preferences.addFrom)
            Shortcut.Kind.PasteMagnet -> pending = magnetFromClipboard(preferences.addFrom)
        }
    }
    var sort by model.sort
    // Read through a state, not captured: the effect is launched once and these change later, so
    // a plain read inside it would be the value from before the click.
    val askedToStop by rememberUpdatedState(stopping)
    val chosenPreferences by rememberUpdatedState(preferences)
    // The dialog runs on the composition and the engine on its own dispatcher; a channel is the
    // seam, so a click never blocks a frame on a torrent being opened and hashed.
    val accepted = model.accepted
    val dhtWanted = model.dhtWanted
    // Conflated: a person dragging a number through 1, 12, 120, 1200 is one final answer, and the
    // three on the way are worth nothing to a running session.
    val retuned = model.retuned
    val commanded = model.commanded

    // The engine's whole lifetime is the holder's, not this composition's: a window thrown away
    // and rebuilt — which on Android is a rotation — must not take the `TorrentSet` with it
    // (B-79). Keyed on the holder so that it is started once per holder and not once per window.
    LaunchedEffect(model, initial, directory) {
        model.start(initial = initial, torrents = torrents, agents = agents, onStopped = onStopped)
    }

    // Whoever built the holder closes it: a window that made its own takes it down with it, and one
    // handed a holder by the application that outlives it leaves the engine running.
    DisposableEffect(model) { onDispose { if (model.ownedByTheWindow) model.close() } }

    // What the window is being asked to do, handed down rather than captured: the loop above
    // reads it on every tick and a value captured at launch would be the one from before the
    // click.
    LaunchedEffect(stopping) { model.stopping.value = stopping }

    val snapshot = engine ?: return
    // Composed here rather than in the loop, so a click is a recomposition and not a wait.
    val ordered = snapshot.samples.inOrder(sort)
    // Built once, unselected, because which row is selected is decided *after* the filter has
    // decided which rows there are.
    // Broken first, then magnets, then sessions. The two that cannot be sorted go above the ones
    // that can: `inOrder` reorders sessions, and a row interleaved into that would move when
    // somebody clicked a column head for reasons having nothing to do with it.
    val everyRow =
        broken.map { brokenRow(it.name) } +
            snapshot.fetching.map { magnetRow(it) } +
            ordered.map { rowOf(it.state, it.rates, snapshot.lifecycle) }
    val everyKey =
        broken.map { it.infoHash } +
            snapshot.fetching.map { it.infoHash.hex() } +
            ordered.map { it.state.infoHash.hex() }
    val kept = everyRow.indices.filter { everyRow[it].matches(filter) }
    val rowKeys = kept.map { everyKey[it] }
    val index = rowKeys.indexOf(selected).coerceAtLeast(0)
    val chosenSample =
        kept.getOrNull(index)?.let { source ->
            ordered.getOrNull(source - snapshot.fetching.size - broken.size)
        }
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
            mappedExternalPort = snapshot.mappedExternalPort,
            dhtNodes = snapshot.dhtNodes,
            heapUsedBytes = snapshot.heapUsedBytes,
            heapMaxBytes = snapshot.heapMaxBytes,
            // The banner names one session because one session failed; which one it is is the row
            // that is tinted.
            sessionError = degraded?.state?.sessionError,
            unopenable = broken.firstOrNull()?.let { it.name to it.problem.orEmpty() },
            details =
                chosenSample?.takeIf { panelOpen }?.let { sample ->
                    detailsOf(
                        state = sample.state,
                        rates = sample.rates,
                        pieceLength = snapshot.pieceLengths[sample.state.infoHash.hex()] ?: 0,
                        // The torrent's own folder, not the settings' default: a restored torrent
                        // keeps the one it was added with, so *Save to* was showing the wrong
                        // folder for every torrent that is not in the default one (B-81).
                        directory = snapshot.directories[sample.state.infoHash.hex()] ?: preferences.directory,
                        paths = snapshot.filePaths[sample.state.infoHash.hex()].orEmpty(),
                        lifecycle = snapshot.lifecycle,
                        tab = tab,
                    )
                },
            // Checked here and not in the dialog: what a file would land on depends on the folder,
            // and the folder is the one thing the dialog lets somebody change.
            adding = pending?.let { refusedIfOccupied(it, snapshot.occupied) },
            dropping = dropping,
            clipboardMagnet = clipboardMagnet,
            detailsWidth = preferences.detailsWidth.dp,
            removing = removing,
            settings =
                if (settingsOpen) {
                    settingsOf(
                        preferences.boundTo(snapshot.listenPort),
                        autostartProblem = autostartProblem,
                    )
                } else {
                    null
                },
            sort = sort,
        )
    // The clipboard is read when the window comes back into focus, and only then: a poll is what
    // puts an application in the system's clipboard-access indicator once a second.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        val magnet = magnetOnClipboard() ?: return@LaunchedEffect
        // Offered once per link. Returning to the window ten times with the same magnet on the
        // clipboard is one offer, and dismissing it is a person saying no to *this* link.
        if (magnet != offeredMagnet) {
            clipboardMagnet = magnet
            offeredMagnet = magnet
        }
    }

    // The status bar's own figures, sent to whatever is drawn outside this composition — the tray's
    // tooltip. Keyed on the figures rather than on the tick, so a second of no change is no work.
    LaunchedEffect(window.status.down, window.status.up, window.status.torrents) { onStatus(window.status) }

    MainWindow(
        window,
        modifier =
            Modifier.dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target =
                    remember {
                        object : DragAndDropTarget {
                            override fun onEntered(event: DragAndDropEvent) {
                                dropping = DroppedFiles.hovering(event.awtTransferable)
                            }

                            override fun onExited(event: DragAndDropEvent) {
                                dropping = emptyList()
                            }

                            override fun onEnded(event: DragAndDropEvent) {
                                dropping = emptyList()
                            }

                            override fun onDrop(event: DragAndDropEvent): Boolean {
                                dropping = emptyList()
                                val path =
                                    DroppedFiles.firstTorrent(DroppedFiles.paths(event.awtTransferable))
                                        ?: return false
                                pendingDrop = path
                                return true
                            }
                        }
                    },
            ),
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
                    pending = chooseTorrent(preferences.addFrom)
                }

                ToolbarCommand.PasteMagnet -> {
                    pending = magnetFromClipboard(preferences.addFrom)
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
                                // **The torrent's own folder, not the settings' default.** The
                                // checkbox beside this line deletes files, so a dialog naming a
                                // folder the data is not in is asking somebody to agree to
                                // something else — the same defect as the *Save to* field in B-85,
                                // in a second place, found by using the application on Windows.
                                where =
                                    snapshot.directories[sample.state.infoHash.hex()]
                                        ?: preferences.directory,
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
        onAddFile = { index, wanted -> pending = pending?.withFile(index, wanted) },
        onSequential = { on -> pending = pending?.sequentially(on) },
        onResizeDetails = { width -> preferences = preferences.withDetailsWidth(width.value) },
        onClipboardAdd = {
            clipboardMagnet?.let { pending = magnetFromClipboard(preferences.addFrom) }
            clipboardMagnet = null
        },
        onClipboardDismiss = { clipboardMagnet = null },
        onAnnounce = {
            rowKeys.getOrNull(index)?.let {
                commanded.trySend(TorrentCommand(it, TorrentCommand.Kind.Announce))
            }
        },
        onTab = { chosenTab -> tab = chosenTab },
        onSelect = { row -> rowKeys.getOrNull(row)?.let { selected = it } },
        onAddTorrent = { pending = chooseTorrent(preferences.addFrom) },
        onBrowse = {
            // Opens where the dialog is currently pointing, not at the setting: browsing twice in
            // one dialog should start from where the first browse landed.
            chooseDirectory("Save to", pending?.shown?.saveTo ?: preferences.addFrom)?.let { chosen ->
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
                    if (change.key == SettingKey.Autostart) {
                        // Written now, not on the way out. A setting whose file is written when the
                        // window closes is one that disagrees with the system for as long as the
                        // window is open, and this is the setting whose whole subject is what the
                        // system does without the window (B-83).
                        val failure = if (change.on) autostart.enable() else autostart.disable()
                        // A refusal puts the toggle back, because the alternative is a checkbox
                        // that says the client will start with the computer while it will not.
                        if (failure != null) {
                            preferences = preferences.toggled(change.key, !change.on)
                            autostartProblem = failure
                        } else {
                            autostartProblem = null
                        }
                    }
                }

                is SettingChange.Typed -> {
                    preferences = preferences.typed(change.key, change.text)
                    // The screen's own footnote says changes apply immediately, and three of them
                    // now do. The rest say on their row why they cannot.
                    retuned.trySend(preferences.typed(change.key, change.text).runtimeOptions())
                }
            }
        },
        onCopy = { text -> copyToClipboard(text) },
        // Straight through: `openFile` is where every refusal is decided, and the sentence it
        // returns is drawn under the list by the panel that asked.
        onOpenFile = { file -> openFile(file) },
        // Named for the panel it comes from: `onSequential` above is the *add dialog's* tick, which
        // decides the order before there is a session to ask.
        onSequentialOrder = { on ->
            chosenSample?.let {
                commanded.trySend(TorrentCommand(it.state.infoHash.hex(), TorrentCommand.Kind.Sequential, on))
            }
        },
        onFilePriority = { row, tier ->
            chosenSample?.let {
                commanded.trySend(
                    TorrentCommand(
                        it.state.infoHash.hex(),
                        TorrentCommand.Kind.Priority,
                        file = row.index,
                        priority = tier,
                    ),
                )
            }
        },
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
 * The dialog, refused when its files would land on a running torrent's.
 *
 * The message names both the path and the other torrent, because "already in use" without either is
 * a refusal a person cannot act on — and the action is right there: *Browse…* is two rows above it.
 */
internal fun refusedIfOccupied(
    pending: Pending,
    occupied: Map<String, String>,
): AddTorrentState {
    val metainfo = pending.metainfo ?: return pending.shown
    val here =
        java.nio.file.Path
            .of(pending.shown.saveTo)
    val clash =
        FileSet
            .pathsIn(here, metainfo)
            .firstNotNullOfOrNull { path -> occupied[path.toString()]?.let { path to it } }
            ?: return pending.shown
    return pending.shown.refused(
        "${clash.first.fileName} here already belongs to ${clash.second} — choose another folder",
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
 * A key press the window turned into a request.
 *
 * A class around an enum rather than the enum itself: the effect that acts on it is keyed on this
 * value, and two presses of the same key have to be two different values or the second one does
 * nothing.
 */
internal class Shortcut(
    val what: Kind,
) {
    internal enum class Kind { OpenFile, PasteMagnet }
}

/**
 * Which of the two shortcuts a key press is, or null.
 *
 * **The three values, not the `KeyEvent`.** In Compose Multiplatform 1.12 a `KeyEvent` wraps an
 * `InternalKeyEvent` that a test cannot construct — building one from `java.awt.event.KeyEvent`
 * compiles and throws `ClassCastException` on the first accessor. Taking what the decision actually
 * reads leaves the whole of it testable and the window's handler one call around it.
 *
 * **[modified] is meta *or* control, decided at the call site.** The empty state prints `⌘O` on
 * macOS and the same keys are Ctrl elsewhere; a handler that checked only one would leave the hint
 * on the Windows build pointing at nothing.
 */
internal fun shortcutFor(
    type: KeyEventType,
    key: Key,
    modified: Boolean,
): Shortcut.Kind? {
    if (type != KeyEventType.KeyDown || !modified) return null
    return when (key) {
        Key.O -> Shortcut.Kind.OpenFile
        Key.V -> Shortcut.Kind.PasteMagnet
        else -> null
    }
}

/**
 * Something to do to one torrent, addressed by its info hash.
 *
 * By hash and not by index: the list is sorted by whatever column was last clicked, and a command
 * that travelled as "row 3" would arrive at whichever torrent row 3 had become.
 */
internal class TorrentCommand(
    val infoHash: String,
    val kind: Kind,
    /** Only [Kind.Sequential] carries this: which way the order is being switched. */
    val on: Boolean = false,
    /** Only [Kind.Priority] carries these: which file, and to which tier (B-106). */
    val file: Int = -1,
    val priority: FilePriority = FilePriority.NORMAL,
) {
    enum class Kind { Pause, Resume, Recheck, Announce, Remove, RemoveWithData, Sequential, Priority }
}

/**
 * One second's worth of what the engine says, and nothing the person decided.
 *
 * The split is the point: this is recomputed on a timer and everything else is recomputed on a
 * click.
 */
internal class EngineSnapshot(
    val samples: List<Sample>,
    val fetching: List<MagnetLink>,
    val pieceLengths: Map<String, Long>,
    /** Path to the name of the torrent that owns it. Two torrents may not write to one file. */
    val occupied: Map<String, String>,
    val filePaths: Map<String, List<String>>,
    val directories: Map<String, String>,
    val listenPort: Int,
    /** The port the router forwards to [listenPort], or null when nothing does (B-103). */
    val mappedExternalPort: Int?,
    val dhtNodes: Int?,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val lifecycle: Lifecycle,
)

internal suspend fun open(
    set: TorrentSet,
    metainfo: Metainfo,
    preferences: Preferences,
    scope: CoroutineScope,
    /** Files unticked in this torrent's own dialog. Not a setting: it is about this torrent. */
    unwanted: Set<Int> = emptySet(),
    sequential: Boolean = false,
    /**
     * Restored paused, which is not the same as started and then paused: see
     * [TorrentRuntime.start]. The disk check still runs — a paused torrent is one that knows what
     * it has and is not asking for the rest.
     */
    paused: Boolean = false,
    /** Files fetched first, restored with the torrent (B-106); the add dialog has no tick for it. */
    high: Set<Int> = emptySet(),
): TorrentRuntime =
    set.add(metainfo, preferences.runtimeOptions(unwanted, sequential, high)).also {
        it.restore()
        it.start(scope, paused)
    }

/** Every session has answered its tracker, closed its peers, flushed and written its record. */
internal suspend fun allStopped(set: TorrentSet): Boolean =
    withTimeoutOrNull(TICK) { set.torrents.forEach { it.awaitStopped() } } != null

/** The file chooser is the platform's, because a file chooser drawn by hand is always worse. */
private fun chooseTorrent(directory: String): Pending? {
    val dialog = FileDialog(null as Frame?, "Add torrent", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".torrent") }
    dialog.directory = directory
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return torrentAt(Path.of(dialog.directory, file), directory)
}

/**
 * A `.torrent` read off the disk, however it was named.
 *
 * Shared by the file chooser, the drop target and `⌘O`: three gestures that arrive at one path, and
 * three copies of this would be three places for the error handling to differ.
 */
private fun torrentAt(
    path: Path,
    directory: String,
): Pending? =
    try {
        val metainfo = MetainfoParser.parse(Files.readAllBytes(path))
        Pending(
            metainfo,
            null,
            addFrom(metainfo, path.fileName.toString(), saveTo = directory, defaultDirectory = directory),
        )
    } catch (unreadable: IOException) {
        System.err.println("kachok: cannot read $path: ${unreadable.message}")
        null
    } catch (malformed: IllegalArgumentException) {
        System.err.println("kachok: $path is not a usable torrent: ${malformed.message}")
        null
    }

/**
 * A magnet on the clipboard, or null — read without deciding anything about it.
 *
 * Separate from [magnetFromClipboard], which parses and builds a dialog: this only answers "is there
 * a link here", which is what the prompt needs and all it should cost to answer.
 */
private fun magnetOnClipboard(): String? =
    try {
        (Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String)
            ?.trim()
            ?.takeIf { it.startsWith("magnet:") }
    } catch (unavailable: UnsupportedFlavorException) {
        null
    } catch (unreadable: IOException) {
        null
    } catch (busy: IllegalStateException) {
        null
    } catch (headless: java.awt.HeadlessException) {
        // There is no clipboard on a machine with no display. Not hypothetical: the window's own
        // end-to-end test runs headless, and this threw out of the focus effect the first time.
        null
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
    } catch (headless: java.awt.HeadlessException) {
        System.err.println("kachok: there is no clipboard on this display")
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
        } catch (headless: java.awt.HeadlessException) {
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

internal fun heapUsed(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

/**
 * How often the window asks the engine what it is doing.
 *
 * **300 ms, not a second.** A second was chosen against the *rates*, and that reasoning does not
 * apply: `downBytesPerSecond` is the engine's own figure over the engine's own window, so sampling
 * it more often reads the same smoothed number more often rather than a noisier one. What a second
 * did cost was everything that is not a rate — a piece count, a peer count, a percentage — sitting
 * up to a second out of date on a screen somebody is watching.
 *
 * Affordable only because the sample is built off the composition's thread; before that, tripling
 * the rate would have tripled the work the UI thread does between clicks.
 */
internal val TICK = 300.milliseconds

/** Ten of them: the same ten seconds the headless client gives a clean stop before it goes. */
internal const val STOP_TICKS = 10

/**
 * How long a settings change waits before it is written down.
 *
 * Long enough that typing a four-digit rate limit is one write; short enough that closing the
 * window straight after a change still lands it.
 */
private val SETTINGS_SETTLE = 500.milliseconds

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
