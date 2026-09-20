package io.github.youndie.kachok.ui.session

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import io.github.youndie.kachok.control.SingleInstance
import io.github.youndie.kachok.control.mcp.McpServer
import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.io.EngineDispatchers
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.SetOptions
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.runtime.fetchMetainfo
import io.github.youndie.kachok.engine.session.FilePriority
import io.github.youndie.kachok.ui.EngineSnapshot
import io.github.youndie.kachok.ui.Fetching
import io.github.youndie.kachok.ui.Pending
import io.github.youndie.kachok.ui.STOP_TICKS
import io.github.youndie.kachok.ui.TICK
import io.github.youndie.kachok.ui.TorrentCommand
import io.github.youndie.kachok.ui.allStopped
import io.github.youndie.kachok.ui.deleteQuietly
import io.github.youndie.kachok.ui.details.DetailsTab
import io.github.youndie.kachok.ui.heapUsed
import io.github.youndie.kachok.ui.main.SortOrder
import io.github.youndie.kachok.ui.open
import io.github.youndie.kachok.ui.remove.RemoveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

/**
 * Everything the window *is*, held where a window is not.
 *
 * `Client` was 770 lines of composable holding twenty `mutableStateOf` values, ten `LaunchedEffect`s
 * and the engine's whole lifetime. On this desktop that is correct and free — a window's lifetime
 * *is* the process's, so `remember` is exactly the right lifetime — and on Android it is not: a
 * configuration change destroys the composition, and with it the selected row, the open panel, the
 * filter, the sort and the `TorrentSet`
 * ([B-79](../../../../../../../../docs/backlog/B-79-the-windows-state-outlives-its-composition.md)).
 *
 * **The state is kept as `MutableState` objects rather than as `var`s with backing fields**, and
 * that is what makes the composable's body the same text it was: `var selected by model.selected`
 * reads and writes exactly as `var selected by remember { mutableStateOf(…) }` did, so the six
 * hundred lines that use these names did not have to be re-read to be moved. Snapshot state is
 * readable and writable from any thread, so nothing here needs the composition to exist.
 *
 * **What is deliberately not here**: the mapping layer. `windowOf`, `rowOf`, `detailsOf`,
 * `settingsOf`, `addFrom`, `inOrder`, `matches` and `ratesOf` are pure functions of a state and a
 * click, tested without a holder, a dispatcher or a lifetime, and most of this module's tests rest
 * on that. Making them methods would have bought nothing and cost it.
 */

internal class ClientModel(
    /** What the settings file said when the window opened, which is this run's starting point. */
    initialPreferences: Preferences,
    /** Whether the system says this client starts with the computer; the file is not the authority. */
    autostartProblem: String? = null,
) {
    // ---- what the person decided, which is most of what has to survive ----

    val panelOpen: MutableState<Boolean> = mutableStateOf(true)
    val tab: MutableState<DetailsTab> = mutableStateOf(DetailsTab.Overview)
    val pending: MutableState<Pending?> = mutableStateOf(null)

    /**
     * The torrent that is selected, by info hash — not the row it is in.
     *
     * Sorting reorders the rows under the selection, and an index would leave the highlight on a
     * different torrent than the one the person clicked.
     */
    val selected: MutableState<String?> = mutableStateOf(null)
    val removing: MutableState<RemoveState?> = mutableStateOf(null)
    val filter: MutableState<String> = mutableStateOf("")
    val sort: MutableState<SortOrder> = mutableStateOf(SortOrder())
    val settingsOpen: MutableState<Boolean> = mutableStateOf(false)
    val dropping: MutableState<List<String>> = mutableStateOf(emptyList())
    val clipboardMagnet: MutableState<String?> = mutableStateOf(null)

    /**
     * The last magnet this window offered, so returning to it ten times does not offer it ten
     * times. Cleared by dismissing, which is a person saying no to *this* link.
     */
    val offeredMagnet: MutableState<String?> = mutableStateOf(null)
    val pendingDrop: MutableState<Path?> = mutableStateOf(null)
    val preferences: MutableState<Preferences> = mutableStateOf(initialPreferences)
    val autostartProblem: MutableState<String?> = mutableStateOf(autostartProblem)

    // ---- what the engine says ----

    /**
     * What the engine says, sampled on the tick — and nothing else.
     *
     * Everything the *person* decides is above and is read in composition, not folded into this. It
     * used to be otherwise: the whole window state was rebuilt inside the sampling loop, so every
     * click waited up to a second to appear
     * ([B-64](../../../../../../../../docs/backlog/B-64-a-click-waited-for-the-tick.md)).
     */
    val engine: MutableState<EngineSnapshot?> = mutableStateOf(null)

    /**
     * Remembered torrents the client could not open.
     *
     * Not a session and not a magnet, so it is not in [engine]; it is decided once, when the list
     * is read, and never changes after.
     */
    val broken: MutableState<List<StoredTorrent>> = mutableStateOf(emptyList())

    // ---- the seams between the composition and the engine ----

    /**
     * A dialog runs on the composition and the engine on its own dispatcher, so every one of these
     * is a channel: a click never blocks a frame on a torrent being opened and hashed.
     */
    val accepted: Channel<Pending> = Channel(Channel.UNLIMITED)
    val dhtWanted: Channel<Boolean> = Channel(Channel.CONFLATED)

    /**
     * Conflated: a person dragging a number through 1, 12, 120, 1200 is one final answer, and the
     * three on the way are worth nothing to a running session.
     */
    val retuned: Channel<RuntimeOptions> = Channel(Channel.CONFLATED)
    val commanded: Channel<TorrentCommand> = Channel(Channel.UNLIMITED)

    /** Where the last torrent actually went, back from the engine loop. Only the latest is the answer. */
    val saved: Channel<String> = Channel(Channel.CONFLATED)

    /** What the window is being asked to do: stop, and tell whoever asked when it has. */
    val stopping: MutableState<Boolean> = mutableStateOf(false)

    /**
     * **The engine runs in the holder's own scope and not in the window's**, which is the half of
     * this that actually moves the lifetime.
     *
     * Lifting the loop's *body* out of the composable changes nothing on its own: a
     * `LaunchedEffect` in the window is cancelled when the window leaves the composition, so the
     * `TorrentSet` would still die with it. What survives a composition is a scope that is not the
     * composition's (B-79).
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var running: Job? = null

    /**
     * The set this holder is running, or null before [start].
     *
     * Exposed so that the thing the item promises can be *asserted* — that this is the same set
     * after a window has been thrown away and rebuilt — rather than inferred from the rows.
     */
    @Volatile
    var set: TorrentSet? = null
        private set

    /**
     * Whether the window built this holder, and must therefore close it.
     *
     * **Whoever builds the holder closes it.** A window that made its own — which is every test of
     * the window, and a desktop that has no use for the seam yet — takes it down with it, or 209
     * tests would each leak an engine. A window handed one by an application that outlives it
     * leaves it alone, which is the case the item is for.
     */
    var ownedByTheWindow: Boolean = false

    /** Starts the engine once. Calling it again while it runs is what a recomposition does. */
    fun start(
        initial: Path?,
        torrents: Path,
        agents: ((SingleInstance.McpSessions?) -> Unit)? = null,
        onStopped: () -> Unit = {},
    ) {
        if (running?.isActive == true) return
        running = scope.launch { run(initial, torrents, agents, onStopped) }
    }

    /** Takes the engine down. Only the owner calls this; see [ownedByTheWindow]. */
    fun close() {
        running?.cancel()
        running = null
    }

    /**
     * The engine, for as long as this holder lives.
     *
     * **This used to be a `LaunchedEffect` in the window**, which is why the window could not be
     * thrown away: the dispatchers, the `TorrentSet`, the three command loops and the sampling tick
     * were all keyed on a composition. Here they are keyed on the holder, and a composition that
     * goes away — a rotation, a window closed to the tray on a platform that destroys it — leaves
     * the torrents downloading (B-79).
     *
     * Suspends until it is cancelled or [stopping] has been honoured, and closes everything it
     * opened on the way out.
     */
    internal suspend fun run(
        initial: Path?,
        torrents: Path,
        agents: ((SingleInstance.McpSessions?) -> Unit)? = null,
        onStopped: () -> Unit = {},
    ) {
        val dispatchers = EngineDispatchers()
        val scope = CoroutineScope(coroutineContext + dispatchers.io + SupervisorJob())
        // The DHT is asked for *here* rather than through `dhtWanted`, because a setting restored
        // from the file was never toggled: the first version of this shipped a window whose status
        // bar said "DHT off" beside a settings screen whose toggle was on.
        val set =
            TorrentSet(
                dispatchers = dispatchers,
                scope = scope,
                options = SetOptions(dht = preferences.value.dht),
            )
        // **An agent drives this engine, not one of its own** (B-117). Registered here and not in
        // `main`, because the set is built on this effect: a socket that answered before there was
        // one would hand an agent a client whose torrent list is empty and whose `add_torrent` has
        // nowhere to go. One server per connected agent, each writing back down its own socket.
        agents?.invoke(
            SingleInstance.McpSessions { write ->
                McpServer(set, scope, Path.of(preferences.value.directory), dispatchers, write)
            },
        )
        this.set = set
        try {
            // **The remembered list first, the command line second.** A torrent named in `argv[0]`
            // that the client already has is not a second torrent; opening it again would be
            // refused by `add`, which throws — so a `.torrent` double-clicked while it is already
            // in the list re-selects nothing and breaks nothing.
            //
            // **Started here and not awaited here, because opening a torrent reads the disk.**
            // `open` checks what is already on the drive before a peer is dialled, and for the
            // torrents somebody actually keeps that is minutes of reading and hashing. This effect
            // runs on the composition's dispatcher — the AWT event thread — so awaiting it held the
            // window: the title bar was drawn, nothing under it ever was, and no click was answered
            // until the last torrent had been checked
            // ([B-115](../../../../../../../docs/backlog/B-115-the-startup-check-runs-on-the-window-s-thread.md)).
            // The sampling loop below starts at once now, and the rows appear as the torrents open,
            // each showing its own check — which is what the verifier's progress was always for.
            scope.launch {
                val remembered = loadStoredTorrents(torrents)
                broken.value = remembered.filter { it.metainfo == null }
                remembered.forEach { stored ->
                    val metainfo = stored.metainfo ?: return@forEach
                    open(
                        set,
                        metainfo,
                        preferences.value.withDirectory(stored.directory),
                        scope,
                        unwanted = stored.unwanted,
                        sequential = stored.sequential,
                        paused = stored.paused,
                        high = stored.high,
                    )
                }
                initial?.let { path ->
                    val metainfo = MetainfoParser.parse(Files.readAllBytes(path))
                    if (set.torrents.none { it.metainfo.infoHash.hex() == metainfo.infoHash.hex() }) {
                        open(set, metainfo, preferences.value, scope)
                        rememberTorrent(torrents, metainfo, preferences.value.directory)
                    }
                }
            }
            scope.launch {
                for (options in retuned) {
                    set.torrents.forEach { it.reconfigure(options) }
                }
            }
            scope.launch {
                for (command in commanded) {
                    val runtime =
                        set.torrents.firstOrNull { it.metainfo.infoHash.hex() == command.infoHash }
                            ?: continue
                    when (command.kind) {
                        TorrentCommand.Kind.Pause -> {
                            runtime.pause()
                            rememberPaused(torrents, command.infoHash, paused = true)
                        }

                        TorrentCommand.Kind.Resume -> {
                            runtime.resume()
                            rememberPaused(torrents, command.infoHash, paused = false)
                        }

                        TorrentCommand.Kind.Recheck -> {
                            runtime.recheck()
                        }

                        TorrentCommand.Kind.Sequential -> {
                            runtime.sequential(command.on)
                            // Written down as well as sent: the order is a decision about this
                            // torrent, and one that does not survive a restart is one somebody has
                            // to take again every time (B-89).
                            rememberSequential(torrents, command.infoHash, command.on)
                        }

                        TorrentCommand.Kind.Priority -> {
                            runtime.prioritise(command.file, command.priority)
                            // Written down as well as sent, like the order: the sets are derived
                            // from what the engine last reported *with this click applied*, rather
                            // than awaited from the next sample, so a window closed a second after
                            // the click still remembers it.
                            val files = runtime.state.value.files
                            val tierOf = { at: Int -> if (at == command.file) command.priority else files[at].priority }
                            rememberPriorities(
                                torrents,
                                command.infoHash,
                                unwanted = files.indices.filter { tierOf(it) == FilePriority.SKIP }.toSet(),
                                high = files.indices.filter { tierOf(it) == FilePriority.HIGH }.toSet(),
                            )
                        }

                        TorrentCommand.Kind.Announce -> {
                            runtime.announce()
                        }

                        TorrentCommand.Kind.Remove -> {
                            set.remove(runtime)
                            forgetTorrent(torrents, command.infoHash)
                        }

                        TorrentCommand.Kind.RemoveWithData -> {
                            // The paths are read *before* the remove: `remove` closes the files,
                            // and a `FileSet` that has been closed is not somewhere to ask what it
                            // was writing.
                            val paths = runtime.paths
                            set.remove(runtime)
                            forgetTorrent(torrents, command.infoHash)
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
                        open(
                            set,
                            it,
                            preferences.value.withDirectory(next.shown.saveTo),
                            scope,
                            unwanted = next.unwanted(),
                            sequential = next.shown.sequential,
                        )
                        // Written after the session opened, not before: `add` refuses a torrent
                        // whose files another one owns, and a list that remembered the refusal
                        // would reopen the collision on every start.
                        rememberTorrent(
                            torrents,
                            it,
                            saveTo = next.shown.saveTo,
                            unwanted = next.unwanted(),
                            sequential = next.shown.sequential,
                        )
                        // So the next add dialog opens where this one ended. The *setting* is left
                        // alone: browsing elsewhere once is not a person changing their default.
                        saved.trySend(next.shown.saveTo)
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
                                    addFrom(waiting.link, preferences.value.directory, preferences.value.directory),
                                ),
                            )
                        }
                    }
                }
                if (stopping.value && !asked) {
                    asked = true
                    set.torrents.forEach { it.stop() }
                }
                val running = set.torrents
                // **Built off the composition's thread.** This runs in a `LaunchedEffect`, which is
                // the UI thread, and everything below reads a state flow per torrent and walks
                // every file of every one of them. At a second a tick that was invisible; at 300 ms
                // it is the difference between a click that lands and a click that waits for the
                // sampler to finish. Only the assignment happens back here, and snapshot state is
                // safe to write from anywhere anyway.
                engine.value =
                    withContext(dispatchers.io) {
                        // Once per torrent, not twice. `paths` walks the `FileSet` and builds a list on
                        // every call, and two maps below wanted it — which is a hundred strings per
                        // torrent per tick, thrown away.
                        val paths = running.associateWith { runtime -> runtime.paths.map { it.toString() } }
                        EngineSnapshot(
                            samples =
                                running.map { runtime ->
                                    val state = runtime.state.value
                                    Sample(state, ratesOf(state))
                                },
                            fetching = fetching.map { it.link },
                            pieceLengths =
                                running.associate {
                                    it.metainfo.infoHash.hex() to it.metainfo.pieceLength.toLong()
                                },
                            // Every file a running torrent owns, so the add dialog can refuse *before*
                            // the button rather than throwing out of `add` after it.
                            occupied =
                                paths
                                    .flatMap { (runtime, files) -> files.map { it to runtime.metainfo.name } }
                                    .toMap(),
                            // Asked of the torrent rather than computed from the settings: the layout
                            // of a multi-file torrent is the `FileSet`'s decision, and a second
                            // implementation of it here would be a second chance to open the wrong
                            // file.
                            filePaths =
                                paths.entries.associate { (runtime, files) ->
                                    runtime.metainfo.infoHash.hex() to
                                        files
                                },
                            directories =
                                running.associate { it.metainfo.infoHash.hex() to it.directory.toString() },
                            listenPort = set.listenPort,
                            mappedExternalPort = set.mappedExternalPort,
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
                    }
                if (!asked) {
                    delay(TICK)
                } else if (allStopped(set) || ++stopTicks >= STOP_TICKS) {
                    break
                }
            }
        } finally {
            // Before the set is closed, not after: an agent that connects in between is told there
            // is no engine, which is true, rather than handed one that is being torn down.
            agents?.invoke(null)
            this.set = null
            set.close()
            dispatchers.close()
            onStopped()
        }
    }
}

/**
 * The holder a window starts from: the settings file read, the system asked about autostart.
 *
 * One function so that the window's default and the application that outlives the window build the
 * same thing. The two reads are here rather than inside [ClientModel] because a holder a test can
 * build is a holder that touches neither.
 */
internal fun clientModelFor(
    directory: Path,
    settingsFile: Path,
    directoryOverrides: Boolean,
): ClientModel {
    val here = directory.toAbsolutePath().toString()
    val autostart = autostartFor()
    val stored = loadPreferences(settingsFile, Preferences(directory = here))
    return ClientModel(
        initialPreferences =
            (if (directoryOverrides) stored.withDirectory(here) else stored)
                // The system is the authority on this one. The file is where the *rest* of the
                // settings live, and it is also where this one is written, but an entry somebody
                // removed by hand means the checkbox is off however the file reads.
                .copy(autostart = autostart.isEnabled()),
        autostartProblem = autostart.refusal,
    )
}
