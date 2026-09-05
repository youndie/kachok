package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.runtime.RuntimeOptions
import ru.workinprogress.kachok.engine.session.SessionConfig
import ru.workinprogress.kachok.engine.tracker.TrackerProtocol
import ru.workinprogress.kachok.ui.settings.Setting
import ru.workinprogress.kachok.ui.settings.SettingsSection
import ru.workinprogress.kachok.ui.settings.SettingsState

/**
 * What a person has actually changed, plus what the process actually did.
 *
 * [port] is the port the listener **bound**, not the one somebody asked for: the status bar says
 * `port 6881 listening` from the same number, and a settings screen that showed the wish while the
 * status bar showed the fact would be two answers to one question the first time 6881 was busy.
 */
internal class Preferences(
    val directory: String,
    val startWhenAdded: Boolean = true,
    val port: Int? = null,
    val maxPeers: Int? = null,
    val pipelineDepth: Int? = null,
    val uploadLimitKibPerSecond: Long? = null,
    val downloadLimitKibPerSecond: Long? = null,
    val dht: Boolean = false,
)

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
): SettingsState {
    val defaultPort = TrackerProtocol.PORT_RANGE.first
    return SettingsState(
        sections =
            listOf(
                SettingsSection(
                    "DOWNLOADS",
                    listOf(
                        Setting(
                            label = "Save to",
                            default = DEFAULT_DIRECTORY,
                            value = preferences.directory,
                            folder = true,
                            changed = preferences.directory != DEFAULT_DIRECTORY,
                        ),
                        Setting(
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
                            label = "Listening port",
                            note =
                                "The first free port of ${TrackerProtocol.PORT_RANGE.first}–" +
                                    "${TrackerProtocol.PORT_RANGE.last} is taken if this one is busy.",
                            default = "$defaultPort",
                            value = "${preferences.port ?: defaultPort}",
                            changed = (preferences.port ?: defaultPort) != defaultPort,
                        ),
                        Setting(
                            label = "Connections to keep up",
                            note = "Also the second term of the buffer pool's working set.",
                            default = "${defaults.maxPeers}",
                            value = "${preferences.maxPeers ?: defaults.maxPeers}",
                            changed = preferences.maxPeers != null && preferences.maxPeers != defaults.maxPeers,
                        ),
                        Setting(
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
                            "Upload limit",
                            "One budget for the session, not one per peer.",
                            preferences.uploadLimitKibPerSecond,
                            defaults.uploadLimitBytesPerSecond,
                        ),
                        limit(
                            "Download limit",
                            null,
                            preferences.downloadLimitKibPerSecond,
                            defaults.downloadLimitBytesPerSecond,
                        ),
                    ),
                ),
                SettingsSection(
                    "PRIVACY",
                    listOf(
                        Setting(
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
        footnote = "Changes apply to the running session immediately — no restart, no Apply button.",
    )
}

/** Where the design says a fresh install saves, and the only string here that is not a measurement. */
internal const val DEFAULT_DIRECTORY: String = "~/Downloads"

/**
 * A limit of `no limit` is not a limit of zero.
 *
 * `SessionConfig` spells that as `0`, which is the one place in this screen where the engine's
 * value and the words a person needs are different things — so the field says the words and zero
 * is not typeable.
 */
private fun limit(
    label: String,
    note: String?,
    chosen: Long?,
    default: Long,
): Setting =
    Setting(
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
