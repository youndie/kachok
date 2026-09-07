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
     * BEP 5, and **off** unless asked for.
     *
     * Mainstream clients join the DHT by default and this one will too, once there is a torrent
     * that needs it — a magnet link, which is
     * [B-36](../../../../../../../docs/backlog/B-36-ut-metadata-and-magnets.md). Until then every
     * torrent this client can open names a tracker, so joining would be contacting three public
     * routers and announcing this machine's address to strangers for no gain. It would also mean
     * every run of the test suite doing it.
     */
    val dht: Boolean,
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
  --dht               join the DHT (BEP 5); a private torrent never does

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
  --dht               join the DHT (BEP 5); a private torrent never does"""

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
        var dht = false
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

                "--dht" -> {
                    dht = true
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
        var dht = false

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

                "--dht" -> {
                    dht = true
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
    private const val DEFAULT_PEERS = 50
    private const val DEFAULT_PIPELINE = 16
}
