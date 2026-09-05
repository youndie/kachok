package ru.workinprogress.kachok.cli

import java.nio.file.Path

/** What `download` was asked to do. */
class DownloadOptions(
    val torrent: Path,
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
        """kachok download <file.torrent> [options]

  --dir <path>        where to write (default: the working directory)
  --port <n>          listening port (default: the first free of 6881-6889)
  --peers <n>         connections to keep up (default: 50)
  --pipeline <n>      requests outstanding per peer (default: 16)
  --seed              keep seeding after the download completes
  --up <KiB/s>        upload limit across all peers (default: no limit)
  --down <KiB/s>      download limit across all peers (default: no limit)
  --dht               join the DHT (BEP 5); a private torrent never does"""

    fun parseDownload(arguments: List<String>): DownloadOptions {
        if (arguments.isEmpty()) throw UsageException("download needs a .torrent file")
        var torrent: Path? = null
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
                    if (torrent != null) throw UsageException("more than one .torrent given")
                    torrent = Path.of(argument)
                }
            }
            index++
        }

        return DownloadOptions(
            torrent = torrent ?: throw UsageException("download needs a .torrent file"),
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

    private const val BYTES_PER_KIB = 1024L
    private const val DEFAULT_PEERS = 50
    private const val DEFAULT_PIPELINE = 16
}
