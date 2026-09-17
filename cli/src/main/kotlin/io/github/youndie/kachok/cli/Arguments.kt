package io.github.youndie.kachok.cli

import io.github.youndie.kachok.cli.serve.ServeOptions
import java.nio.file.Path

/** Where the torrent comes from: a file on this machine, or an identifier and a swarm. */
sealed interface TorrentSource {
    class File(
        val path: Path,
    ) : TorrentSource {
        override fun toString(): String = path.toString()
    }

    class Magnet(
        val uri: String,
    ) : TorrentSource {
        override fun toString(): String = uri.take(MAGNET_IN_MESSAGES)

        private companion object {
            /** Enough of it to recognise, short enough not to fill an error message. */
            const val MAGNET_IN_MESSAGES = 60
        }
    }
}

/** What `download` was asked to do. */
class DownloadOptions(
    val source: TorrentSource,
    val directory: Path,
    val port: Int?,
    val maxPeers: Int,
    val pipelineDepth: Int,
    val seedAfterCompletion: Boolean,
    /** Bytes a second, across every peer. Zero means no limit, which is the default. */
    val uploadLimit: Long,
    val downloadLimit: Long,
    /**
     * BEP 5, and **on** unless `--no-dht` says otherwise.
     *
     * This used to be off, with a comment promising it would change "once there is a torrent that
     * needs it — a magnet link". That arrived with
     * [B-36](../../../../../../../docs/backlog/B-36-ut-metadata-and-magnets.md) and the default was
     * not revisited, so the sentence spent months arguing for the opposite of what it said
     * ([B-99](../../../../../../../docs/backlog/B-99-the-dht-is-off-and-its-reason-for-being-off-expired.md)).
     *
     * What settled it is a measurement rather than a promise: `torrent.ubuntu.com` hands out
     * **one** peer per announce, at `numwant` unset, 50 and 200 alike, against a swarm of 526. A
     * client without the DHT does not get a small share of that swarm, it gets one address. The
     * half of the old reason that has not expired is kept where it belongs: the engine's own
     * default stays off, so a test suite still does not contact three public routers by
     * constructing a `TorrentSet`.
     */
    val dht: Boolean,
    /**
     * Ask every tracker the torrent names rather than the first that answers.
     *
     * Off, because BEP 12 asks clients to stop at the first working tracker and because on a
     * public torrent the rest mostly hold the same peers. Worth turning on for a swarm split
     * across trackers that do not share one.
     */
    val announceToAllTrackers: Boolean,
)

/** A command line that does not parse, with the reason a user can act on. */
class UsageException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Hand-written, because the surface is one command and six flags.
 *
 * A parsing library would be a dependency in the run-time image for sixty lines of code, and the
 * error messages a user actually reads would be its rather than ours.
 */
object Arguments {
    const val USAGE: String =
        """kachok download <file.torrent | magnet:?xt=…> [options]

  --dir <path>        where to write (default: the working directory)
  --port <n>          listening port (default: the first free of 6881-6889)
  --peers <n>         connections to keep up (default: 50)
  --pipeline <n>      requests outstanding per peer (default: 16)
  --seed              keep seeding after the download completes
  --up <KiB/s>        upload limit across all peers (default: no limit)
  --down <KiB/s>      download limit across all peers (default: no limit)
  --no-dht            stay out of the DHT (BEP 5), which is joined by default

kachok serve [options]

  Runs the engine with a WebSocket on loopback and no window, for a client that
  is not in this process — the browser build of the UI (B-40).

  --dir <path>        where to write (default: the working directory)
  --ws-port <n>       the WebSocket port (default: 0, meaning the OS picks one)
  --port <n>          peer listening port (default: the first free of 6881-6889)
  --origin <url>      a page origin allowed to connect, repeatable. Without it
                      no browser page may connect at all: a WebSocket is not
                      subject to the same-origin rule, so any site could
                      otherwise drive this client.
  --no-dht            stay out of the DHT (BEP 5), which is joined by default
  --all-trackers      ask every tracker, not the first that answers (BEP 12)"""

    /**
     * `serve`'s options.
     *
     * `--origin` is repeatable and everything else is not: a page served over two addresses is two
     * origins, and picking one of them for somebody is picking the wrong one half the time.
     */
    fun parseServe(arguments: List<String>): ServeOptions {
        var directory = Path.of(".")
        var port = 0
        var peerPort: Int? = null
        var dht = true
        val origins = mutableSetOf<String>()
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--dir" -> {
                    directory = Path.of(value(arguments, ++index, argument))
                }

                // Zero is a legitimate answer here and `number` refuses it — it means "ask the
                // operating system", and the line the backend prints is what says which port it got.
                "--ws-port" -> {
                    val text = value(arguments, ++index, argument)
                    port = text.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw UsageException("$argument needs a port, got '$text'")
                }

                "--port" -> {
                    peerPort = number(value(arguments, ++index, argument), argument)
                }

                "--origin" -> {
                    origins += value(arguments, ++index, argument)
                }

                "--no-dht" -> {
                    dht = false
                }

                else -> {
                    throw UsageException("unknown option '$argument'")
                }
            }
            index++
        }
        return ServeOptions(directory, port, peerPort, dht, origins)
    }

    fun parseDownload(arguments: List<String>): DownloadOptions {
        if (arguments.isEmpty()) throw UsageException("download needs a .torrent file or a magnet link")
        var source: TorrentSource? = null
        var directory = Path.of(".")
        var port: Int? = null
        var maxPeers = DEFAULT_PEERS
        var pipeline = DEFAULT_PIPELINE
        var seed = false
        var upload = 0L
        var download = 0L
        var dht = true
        var allTrackers = false

        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--dir" -> {
                    directory = Path.of(value(arguments, ++index, argument))
                }

                "--port" -> {
                    port = number(value(arguments, ++index, argument), argument)
                }

                "--peers" -> {
                    maxPeers = number(value(arguments, ++index, argument), argument)
                }

                "--pipeline" -> {
                    pipeline = number(value(arguments, ++index, argument), argument)
                }

                "--seed" -> {
                    seed = true
                }

                // Kibibytes a second on the command line, bytes a second inside: nobody types a
                // rate limit in bytes, and nobody wants the engine's arithmetic to have a unit in
                // it that only the command line uses.
                "--up" -> {
                    upload = number(value(arguments, ++index, argument), argument).toLong() * BYTES_PER_KIB
                }

                "--no-dht" -> {
                    dht = false
                }

                "--all-trackers" -> {
                    allTrackers = true
                }

                "--down" -> {
                    download = number(value(arguments, ++index, argument), argument).toLong() * BYTES_PER_KIB
                }

                else -> {
                    if (argument.startsWith("--")) throw UsageException("unknown option '$argument'")
                    if (source != null) throw UsageException("more than one torrent given")
                    source =
                        if (argument.startsWith(MAGNET_SCHEME)) {
                            TorrentSource.Magnet(argument)
                        } else {
                            TorrentSource.File(Path.of(argument))
                        }
                }
            }
            index++
        }

        return DownloadOptions(
            source = source ?: throw UsageException("download needs a .torrent file or a magnet link"),
            directory = directory,
            port = port,
            maxPeers = maxPeers,
            pipelineDepth = pipeline,
            seedAfterCompletion = seed,
            uploadLimit = upload,
            downloadLimit = download,
            dht = dht,
            announceToAllTrackers = allTrackers,
        )
    }

    private fun value(
        arguments: List<String>,
        at: Int,
        option: String,
    ): String = arguments.getOrNull(at) ?: throw UsageException("$option needs a value")

    private fun number(
        text: String,
        option: String,
    ): Int =
        text.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("$option needs a positive number, got '$text'")

    private const val MAGNET_SCHEME = "magnet:"
    private const val BYTES_PER_KIB = 1024L
    private const val DEFAULT_PEERS = 250
    private const val DEFAULT_PIPELINE = 16
}
