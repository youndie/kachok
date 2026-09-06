package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.session.SessionConfig
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import ru.workinprogress.kachok.ui.settings.Setting
import ru.workinprogress.kachok.ui.settings.SettingKey
import ru.workinprogress.kachok.ui.settings.SettingsSection
import ru.workinprogress.kachok.ui.settings.SettingsState

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
    val uploadLimitKibPerSecond: Long? = null,
    val downloadLimitKibPerSecond: Long? = null,
    val dht: Boolean = false,
    val autostart: Boolean = false,
    /**
     * How wide the details panel is, in dp.
     *
     * A setting and not window state: a panel a person widened once and finds back at 340 every
     * launch is a panel they widen every launch. It is not on the settings screen — the way to set
     * it is to drag it.
     */
    val detailsWidth: Float = DEFAULT_DETAILS_WIDTH,
) {
    /** The same preferences with the port the listener actually bound written into them. */
    fun boundTo(port: Int): Preferences = copy(port = port)

    fun withDirectory(path: String): Preferences = copy(directory = path)

    /** Clamped here rather than at the drag, so no caller can store a width the panel cannot draw. */
    fun withDetailsWidth(width: Float): Preferences =
        copy(detailsWidth = width.coerceIn(MIN_DETAILS_WIDTH, MAX_DETAILS_WIDTH))

    fun toggled(
        key: SettingKey,
        on: Boolean,
    ): Preferences =
        when (key) {
            SettingKey.StartWhenAdded -> copy(startWhenAdded = on)
            SettingKey.Dht -> copy(dht = on)
            SettingKey.Autostart -> copy(autostart = on)
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
    ): RuntimeOptions =
        RuntimeOptions(
            directory =
                java.nio.file.Path
                    .of(directory),
            maxPeers = maxPeers ?: RuntimeOptions.DEFAULT_MAX_PEERS,
            pipelineDepth = pipelineDepth ?: RuntimeOptions.DEFAULT_PIPELINE,
            uploadLimitBytesPerSecond = (uploadLimitKibPerSecond ?: 0) * KIB,
            downloadLimitBytesPerSecond = (downloadLimitKibPerSecond ?: 0) * KIB,
            unwantedFiles = unwanted,
            sequential = sequential,
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
                            "One budget for the session, not one per peer.",
                            preferences.uploadLimitKibPerSecond,
                            defaults.uploadLimitBytesPerSecond,
                        ),
                        limit(
                            SettingKey.DownloadLimit,
                            "Download limit",
                            null,
                            preferences.downloadLimitKibPerSecond,
                            defaults.downloadLimitBytesPerSecond,
                        ),
                    ),
                ),
                SettingsSection(
                    "STARTUP",
                    listOf(
                        Setting(
                            key = SettingKey.Autostart,
                            label = "Start with the computer",
                            note =
                                autostartProblem
                                    ?: (
                                        "Starts minimised, so a login is not interrupted by a " +
                                            "window."
                                    ),
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
                                    "with three public bootstrap routers. kachok only needs it for " +
                                    "a magnet link that names no tracker, so it is off until you " +
                                    "ask. A private torrent never joins, whatever this says.",
                            default = "off",
                            value = "",
                            toggle = preferences.dht,
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
