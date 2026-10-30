package io.github.youndie.kachok.ui.session

import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import io.github.youndie.kachok.engine.runtime.SetLimits
import io.github.youndie.kachok.engine.session.SessionConfig
import io.github.youndie.kachok.engine.tracker.TrackerProtocol
import io.github.youndie.kachok.ui.settings.Choice
import io.github.youndie.kachok.ui.settings.Setting
import io.github.youndie.kachok.ui.settings.SettingKey
import io.github.youndie.kachok.ui.settings.SettingsSection
import io.github.youndie.kachok.ui.settings.SettingsState
import io.github.youndie.kachok.ui.theme.InterfaceKit

/**
 * What a person has actually changed, plus what the process actually did.
 *
 * [port] is the port the listener **bound**, not the one somebody asked for: the status bar says
 * `port 6881 listening` from the same number, and a settings screen that showed the wish while the
 * status bar showed the fact would be two answers to one question the first time 6881 was busy.
 */
internal data class Preferences(
    val directory: String,
    val startWhenAdded: Boolean = true,
    val port: Int? = null,
    val maxPeers: Int? = null,
    val pipelineDepth: Int? = null,
    /** The whole client's, not each torrent's, since B-151; `null` is no limit. */
    val uploadLimitKibPerSecond: Long? = null,
    val downloadLimitKibPerSecond: Long? = null,
    /** Downloads at once, the rest queued (B-151); `null` is no limit. */
    val maxActiveDownloads: Int? = null,
    /** Connections and dials in flight for the whole client (B-152); `null` is the recommended cap, 0 none. */
    val maxConnections: Int? = null,
    val maxDials: Int? = null,
    /**
     * Join the DHT, and **on** since B-99.
     *
     * The measurement that decided it: on a public torrent whose tracker hands out one peer per
     * announce — which `torrent.ubuntu.com` does, at every `numwant` — a client without the DHT
     * holds one peer and a client with it holds thirty. The engine's own default stays off, because
     * a library must not announce this machine to strangers merely by being constructed; this is
     * the product deciding for the person who installed it.
     */
    val dht: Boolean = true,
    /** Ask every tracker the torrent names rather than only the first that answers (BEP 12). */
    val announceToAllTrackers: Boolean = false,
    val autostart: Boolean = false,
    /** Closing the window leaves the client running in the tray rather than stopping the torrents. */
    val closeToTray: Boolean = true,
    /**
     * Whether the tray has already explained itself once.
     *
     * The failure this exists for: somebody presses close, sees nothing happen, presses it again,
     * and concludes the application is broken. One notice the first time answers that; a notice
     * every time is an application that nags.
     */
    val trayExplained: Boolean = false,
    /**
     * The folder the last torrent was actually saved to, which is not the *setting*.
     *
     * Somebody who browses elsewhere for one torrent expects the next add dialog to open there, and
     * nothing else about their configuration to have changed. Writing it into [directory] would do
     * the first by doing the second: the settings screen would quietly show a folder they never
     * chose as a default, and the row would say `changed` about a decision they made once.
     */
    val lastDirectory: String? = null,
    /**
     * How wide the details panel is, in dp.
     *
     * A setting and not window state: a panel a person widened once and finds back at 340 every
     * launch is a panel they widen every launch. It is not on the settings screen — the way to set
     * it is to drag it.
     */
    val detailsWidth: Float = DEFAULT_DETAILS_WIDTH,
    /**
     * Which kit draws the window (B-160). Applied as it is chosen: the kit is a composition local,
     * so the window redraws in it without a restart.
     */
    val interfaceKit: InterfaceKit = InterfaceKit.DEFAULT,
) {
    /** The same preferences with the port the listener actually bound written into them. */
    fun boundTo(port: Int): Preferences = copy(port = port)

    fun withDirectory(path: String): Preferences = copy(directory = path)

    /** Clamped here rather than at the drag, so no caller can store a width the panel cannot draw. */
    fun withDetailsWidth(width: Float): Preferences =
        copy(detailsWidth = width.coerceIn(MIN_DETAILS_WIDTH, MAX_DETAILS_WIDTH))

    /** Where an add dialog should open: where the last one ended, or the setting on a first run. */
    val addFrom: String get() = lastDirectory?.takeIf { it.isNotBlank() } ?: directory

    fun toggled(
        key: SettingKey,
        on: Boolean,
    ): Preferences =
        when (key) {
            SettingKey.StartWhenAdded -> copy(startWhenAdded = on)
            SettingKey.Dht -> copy(dht = on)
            SettingKey.AllTrackers -> copy(announceToAllTrackers = on)
            SettingKey.Autostart -> copy(autostart = on)
            SettingKey.CloseToTray -> copy(closeToTray = on)
            else -> this
        }

    /** A segment picked on a choice row; a value nobody offered changes nothing it does not know. */
    fun chose(
        key: SettingKey,
        value: String,
    ): Preferences =
        when (key) {
            SettingKey.Interface -> copy(interfaceKit = InterfaceKit.of(value))
            else -> this
        }

    /**
     * A number, as far as it is a number.
     *
     * Empty is not zero and not an error: it is a field somebody is halfway through clearing, and
     * it keeps the default until there is something to read. A limit of nothing stays `no limit`,
     * which is the one place where the words and the engine's `0` are different things.
     */
    fun typed(
        key: SettingKey,
        text: String,
    ): Preferences {
        val digits = text.filter { it.isDigit() }
        val number = digits.toLongOrNull()
        return when (key) {
            SettingKey.MaxPeers -> copy(maxPeers = number?.toInt())
            SettingKey.PipelineDepth -> copy(pipelineDepth = number?.toInt())
            SettingKey.UploadLimit -> copy(uploadLimitKibPerSecond = number)
            SettingKey.DownloadLimit -> copy(downloadLimitKibPerSecond = number)
            SettingKey.MaxActiveDownloads -> copy(maxActiveDownloads = number?.toInt())
            SettingKey.MaxConnections -> copy(maxConnections = number?.toInt())
            SettingKey.MaxDials -> copy(maxDials = number?.toInt())
            else -> this
        }
    }

    /**
     * What the next torrent is opened with.
     *
     * The port is not here: the listener is the set's and is bound once. A limit is given in
     * kibibytes on the screen and in bytes to the engine, which is the same conversion the headless
     * client makes from `--up` and `--down`.
     *
     * [unwanted] is a parameter and not a field for the same reason: everything else here is a
     * setting that outlives this torrent, and which files to skip is a decision about this one.
     */
    fun runtimeOptions(
        unwanted: Set<Int> = emptySet(),
        sequential: Boolean = false,
        high: Set<Int> = emptySet(),
    ): RuntimeOptions =
        RuntimeOptions(
            directory =
                java.nio.file.Path
                    .of(directory),
            maxPeers = maxPeers ?: RuntimeOptions.DEFAULT_MAX_PEERS,
            pipelineDepth = pipelineDepth ?: RuntimeOptions.DEFAULT_PIPELINE,
            // Not per torrent any more: the limits are the set's (B-151), and a torrent that carried
            // the same number on its own would be limited twice by one setting.
            uploadLimitBytesPerSecond = RuntimeOptions.NO_LIMIT,
            downloadLimitBytesPerSecond = RuntimeOptions.NO_LIMIT,
            announceToAllTrackers = announceToAllTrackers,
            unwantedFiles = unwanted,
            highFiles = high,
            sequential = sequential,
        )
}

/** The set's limits as this screen chose them (B-151). Empty is no limit, which the engine calls 0. */
internal fun Preferences.setLimits(): SetLimits =
    SetLimits(
        downloadBytesPerSecond = (downloadLimitKibPerSecond ?: 0) * KIB,
        uploadBytesPerSecond = (uploadLimitKibPerSecond ?: 0) * KIB,
        maxActiveDownloads = maxActiveDownloads ?: 0,
        maxConnections = maxConnections ?: SetLimits.RECOMMENDED_MAX_CONNECTIONS,
        maxDialsInFlight = maxDials ?: SetLimits.RECOMMENDED_MAX_DIALS,
    )

/**
 * The screen's figures for limits somebody else set — an agent, a browser — so the screen shows
 * what the client is doing rather than what was typed last. A rate that is not a whole number of
 * kibibytes is shown rounded down; it is changed only if somebody edits the row.
 */
internal fun Preferences.withLimits(limits: SetLimits): Preferences =
    if (setLimits() == limits) {
        this
    } else {
        copy(
            downloadLimitKibPerSecond = (limits.downloadBytesPerSecond / KIB).takeIf { it > 0 },
            uploadLimitKibPerSecond = (limits.uploadBytesPerSecond / KIB).takeIf { it > 0 },
            maxActiveDownloads = limits.maxActiveDownloads.takeIf { it > 0 },
            maxConnections = limits.maxConnections.takeIf { it != SetLimits.RECOMMENDED_MAX_CONNECTIONS },
            maxDials = limits.maxDialsInFlight.takeIf { it != SetLimits.RECOMMENDED_MAX_DIALS },
        )
    }

/** The design's own three: 340 drawn, 280–520 allowed. Floats because a drag is in fractions. */
internal const val DEFAULT_DETAILS_WIDTH: Float = 340f

internal const val MIN_DETAILS_WIDTH: Float = 280f

internal const val MAX_DETAILS_WIDTH: Float = 520f

private const val KIB = 1024L

/**
 * The settings screen, with every default read out of `SessionConfig` rather than typed here.
 *
 * That is the acceptance criterion of
 * [B-51](../../../../../../../../docs/backlog/B-51-empty-and-settings.md), and it is the point of
 * the screen: these numbers were measured (research §1.2c, §1.2d), and a default written down a
 * second time is a default that goes stale the first time a measurement changes one.
 *
 * `SessionConfig()` is constructed here for its defaults, which is what a default *is* — the value
 * the engine uses when nobody says otherwise.
 */
internal fun settingsOf(
    preferences: Preferences,
    defaults: SessionConfig = SessionConfig(),
    /**
     * Why the client cannot start with the computer, or what went wrong when it tried.
     *
     * On the row and not in the footnote: the footnote is about the screen, and this is about one
     * checkbox that has just refused to stay pressed.
     */
    autostartProblem: String? = null,
    /** Why this desktop has no tray to close to, or null when it has one. */
    trayProblem: String? = null,
): SettingsState {
    val defaultPort = TrackerProtocol.PORT_RANGE.first
    return SettingsState(
        sections =
            listOf(
                SettingsSection(
                    "DOWNLOADS",
                    listOf(
                        Setting(
                            key = SettingKey.SaveTo,
                            label = "Save to",
                            default = DEFAULT_DIRECTORY,
                            value = preferences.directory,
                            folder = true,
                            changed = preferences.directory != DEFAULT_DIRECTORY,
                        ),
                        Setting(
                            key = SettingKey.StartWhenAdded,
                            label = "Start torrents when added",
                            default = "on",
                            value = "",
                            toggle = preferences.startWhenAdded,
                        ),
                    ),
                ),
                SettingsSection(
                    "NETWORK",
                    listOf(
                        Setting(
                            key = SettingKey.ListeningPort,
                            label = "Listening port",
                            note =
                                "The first free port of ${TrackerProtocol.PORT_RANGE.first}–" +
                                    "${TrackerProtocol.PORT_RANGE.last} is taken if this one is busy.",
                            default = "$defaultPort",
                            value = "${preferences.port ?: defaultPort}",
                            changed = (preferences.port ?: defaultPort) != defaultPort,
                        ),
                        Setting(
                            key = SettingKey.MaxPeers,
                            label = "Connections to keep up",
                            note = "Also the second term of the buffer pool's working set.",
                            default = "${defaults.maxPeers}",
                            value = "${preferences.maxPeers ?: defaults.maxPeers}",
                            changed = preferences.maxPeers != null && preferences.maxPeers != defaults.maxPeers,
                        ),
                        Setting(
                            key = SettingKey.AllTrackers,
                            label = "Ask every tracker",
                            note = "Off asks the first that answers, which is what BEP 12 wants.",
                            default = "off",
                            value = "",
                            toggle = preferences.announceToAllTrackers,
                        ),
                        Setting(
                            key = SettingKey.PipelineDepth,
                            label = "Requests outstanding per peer",
                            note = "Too few idles the link; too many hold pool buffers.",
                            default = "${defaults.pipelineDepth}",
                            value = "${preferences.pipelineDepth ?: defaults.pipelineDepth}",
                            changed =
                                preferences.pipelineDepth != null &&
                                    preferences.pipelineDepth != defaults.pipelineDepth,
                        ),
                    ),
                ),
                SettingsSection(
                    "LIMITS",
                    listOf(
                        limit(
                            SettingKey.UploadLimit,
                            "Upload limit",
                            "Every torrent together, not each one. 1 220 KiB/s is 10 Mbit/s.",
                            preferences.uploadLimitKibPerSecond,
                            defaults.uploadLimitBytesPerSecond,
                        ),
                        limit(
                            SettingKey.DownloadLimit,
                            "Download limit",
                            "Every torrent together, not each one.",
                            preferences.downloadLimitKibPerSecond,
                            defaults.downloadLimitBytesPerSecond,
                        ),
                        Setting(
                            key = SettingKey.MaxActiveDownloads,
                            label = "Downloads at once",
                            note =
                                "The others wait as Queued and start in the order they were added. " +
                                    "Seeding never waits.",
                            default = "none",
                            value = preferences.maxActiveDownloads?.toString() ?: NO_LIMIT,
                            changed = preferences.maxActiveDownloads != null,
                            absent = preferences.maxActiveDownloads == null,
                        ),
                        Setting(
                            key = SettingKey.MaxConnections,
                            label = "Connections, all torrents",
                            note = "Shared out among the torrents transferring. 0 is no cap.",
                            default = "${SetLimits.RECOMMENDED_MAX_CONNECTIONS}",
                            value = "${preferences.maxConnections ?: SetLimits.RECOMMENDED_MAX_CONNECTIONS}",
                            changed =
                                preferences.maxConnections != null &&
                                    preferences.maxConnections != SetLimits.RECOMMENDED_MAX_CONNECTIONS,
                        ),
                        Setting(
                            key = SettingKey.MaxDials,
                            label = "Connection attempts at once",
                            note = "Each waits in the router's table until it answers or times out. 0 is no cap.",
                            default = "${SetLimits.RECOMMENDED_MAX_DIALS}",
                            value = "${preferences.maxDials ?: SetLimits.RECOMMENDED_MAX_DIALS}",
                            changed =
                                preferences.maxDials != null && preferences.maxDials != SetLimits.RECOMMENDED_MAX_DIALS,
                        ),
                    ),
                ),
                SettingsSection(
                    "STARTUP",
                    listOf(
                        Setting(
                            key = SettingKey.CloseToTray,
                            label = "Close to the tray",
                            note =
                                trayProblem
                                    ?: (
                                        "The close button leaves the torrents running. The tray " +
                                            "icon brings the window back, and quits."
                                    ),
                            default = "on",
                            value = "",
                            toggle = preferences.closeToTray && trayProblem == null,
                            enabled = trayProblem == null,
                        ),
                        Setting(
                            key = SettingKey.Autostart,
                            label = "Start with the computer",
                            note =
                                autostartProblem
                                    ?: if (trayProblem == null) {
                                        "Starts in the tray, so a login is not interrupted by a window."
                                    } else {
                                        "Starts minimised, so a login is not interrupted by a window."
                                    },
                            default = "off",
                            value = "",
                            toggle = preferences.autostart,
                        ),
                    ),
                ),
                SettingsSection(
                    "PRIVACY",
                    listOf(
                        Setting(
                            key = SettingKey.Dht,
                            label = "Join the DHT (BEP 5)",
                            note =
                                "Joining announces this machine's address to strangers, starting " +
                                    "with three public bootstrap routers. On, because most public " +
                                    "trackers hand out a handful of peers and the rest of the swarm " +
                                    "is only reachable here. A private torrent never joins, " +
                                    "whatever this says.",
                            default = "on",
                            value = "",
                            toggle = preferences.dht,
                        ),
                    ),
                ),
                // Last, below everything the client *does*: how it looks is the one setting that
                // changes nothing about a torrent (B-160).
                SettingsSection(
                    "INTERFACE",
                    listOf(
                        Setting(
                            key = SettingKey.Interface,
                            label = "Interface",
                            note = "How the window is drawn. Applies at once.",
                            default = InterfaceKit.DEFAULT.label,
                            value = preferences.interfaceKit.stored,
                            choices = InterfaceKit.entries.map { Choice(it.stored, it.label) },
                            changed = preferences.interfaceKit != InterfaceKit.DEFAULT,
                        ),
                    ),
                ),
            ),
        // The badge is gone from the footnote and moved onto the two rows it is still not true of.
        // A sentence that is right about three settings and wrong about two is worse than two rows
        // each saying which they are.
        footnote =
            "Rate limits and the peer count apply to running torrents immediately — " +
                "no restart, no Apply button.",
        footnotePlanned = false,
    )
}

/**
 * Where a fresh install saves.
 *
 * The design writes `~/Downloads` and this resolves it, because the row prints it as the default
 * and a default the application never uses is a lie printed on every row. `main` starts there too.
 */
internal val DEFAULT_DIRECTORY: String =
    java.nio.file.Path
        .of(System.getProperty("user.home"), "Downloads")
        .toString()

/**
 * A limit of `no limit` is not a limit of zero.
 *
 * `SessionConfig` spells that as `0`, which is the one place in this screen where the engine's
 * value and the words a person needs are different things — so the field says the words and zero
 * is not typeable.
 */
private fun limit(
    key: SettingKey,
    label: String,
    note: String?,
    chosen: Long?,
    default: Long,
): Setting =
    Setting(
        key = key,
        label = label,
        note = note,
        default = if (default == RuntimeOptions.NO_LIMIT) "none" else "$default",
        value = chosen?.let { grouped(it) } ?: NO_LIMIT,
        unit = "KiB/s",
        changed = chosen != null,
        absent = chosen == null,
    )

/** `12 000`, grouped the way every other figure in this UI is. */
private fun grouped(value: Long): String =
    value
        .toString()
        .reversed()
        .chunked(GROUP)
        .joinToString(" ")
        .reversed()

private const val NO_LIMIT = "no limit"
private const val GROUP = 3
