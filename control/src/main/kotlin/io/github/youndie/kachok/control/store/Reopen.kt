package io.github.youndie.kachok.control.store

import io.github.youndie.kachok.engine.metainfo.Metainfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Opens every remembered torrent, and keeps trying the ones that could not be opened yet
 * ([B-138](../../../../../../../../docs/backlog/B-138-one-missing-drive-does-not-cost-the-list.md)).
 *
 * **One torrent's failure is that torrent's.** The loop this replaces — in `kachok mcp` and in the
 * window alike — let the first exception out, and a client started before drive `D:` had woken up
 * opened none of its ten torrents, because the first one saved to `D:\Games` threw
 * `FileSystemException: Unable to determine if root directory exists`. Nothing said so where a
 * person would see it: the list was simply empty.
 *
 * Two kinds of failure, told apart by what retrying could change:
 * - [IllegalArgumentException] is the set refusing — the torrent is already open (an agent added
 *   it in the moment since the start) or its files belong to another. Reported, not retried.
 * - anything else — a drive that is not there yet, a directory that cannot be created — is
 *   reported once and tried again after each of [retryAfter], then every [retryForever], for as
 *   long as the torrent is still in [store]: removing it while it waits is how a person says
 *   "stop trying".
 *
 * [open] has to leave nothing behind when it throws; an opened-but-unrestored torrent would make
 * every later attempt a refusal.
 */
public suspend fun reopenStored(
    stored: List<StoredTorrent>,
    store: Path,
    open: suspend (StoredTorrent, Metainfo) -> Unit,
    report: (String) -> Unit,
    retryAfter: List<Duration> = DEFAULT_RETRIES,
    retryForever: Duration = RETRY_FOREVER,
) {
    var waiting = stored.filter { it.metainfo != null }
    val reported = mutableSetOf<String>()
    var attempt = 0
    while (true) {
        waiting =
            waiting.filter { entry ->
                // Forgotten while it waited: removed from the list, so not wanted any more.
                if (attempt > 0 && !Files.exists(store.resolve("${entry.infoHash}$PROPERTIES"))) return@filter false
                try {
                    open(entry, entry.metainfo!!)
                    if (entry.infoHash in reported) report("${entry.name} was reopened")
                    false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: IllegalArgumentException) {
                    report("${entry.name} was not reopened: ${refused.message}")
                    false
                } catch (unavailable: Exception) {
                    if (reported.add(entry.infoHash)) {
                        report("${entry.name} cannot be opened yet (${unavailable.message}); trying again")
                    }
                    true
                }
            }
        if (waiting.isEmpty()) return
        delay(retryAfter.getOrElse(attempt) { retryForever })
        attempt++
    }
}

private const val PROPERTIES = ".properties"

/** Every thirty seconds for the first five minutes: a drive waking up, a share mounting at login. */
private val DEFAULT_RETRIES: List<Duration> = List(10) { 30.seconds }

/** And then every five minutes for as long as the torrent is in the list. */
private val RETRY_FOREVER: Duration = 5.minutes
