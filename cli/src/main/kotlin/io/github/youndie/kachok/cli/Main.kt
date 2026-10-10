package io.github.youndie.kachok.cli

import io.github.youndie.kachok.cli.serve.Serve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * The headless client.
 *
 * Exit codes are the contract a script depends on: `0` complete, `1` the download failed for a
 * reason on stderr, `2` the command line does not parse.
 */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), System.out, System.err))
}

object Cli {
    /**
     * Runs one command and returns its exit code.
     *
     * Takes its streams rather than reaching for `System.out`, so that a test can read what a user
     * would have seen instead of asserting on a side effect it cannot capture.
     */
    fun run(
        arguments: List<String>,
        out: Appendable,
        err: Appendable,
    ): Int {
        val command = arguments.firstOrNull()
        return when (command) {
            "download" -> {
                try {
                    download(Arguments.parseDownload(arguments.drop(1)), out, err)
                } catch (usage: UsageException) {
                    err.appendLine("kachok: ${usage.message}")
                    err.appendLine(Arguments.USAGE)
                    Download.EXIT_USAGE
                }
            }

            "serve" -> {
                try {
                    io.github.youndie.kachok.cli.serve.Serve
                        .run(Arguments.parseServe(arguments.drop(1)), out, err)
                } catch (usage: UsageException) {
                    err.appendLine("kachok: ${usage.message}")
                    err.appendLine(Arguments.USAGE)
                    Download.EXIT_USAGE
                }
            }

            "mcp" -> {
                try {
                    // The protocol owns stdout; `out` is where its frames go and `err` is the only
                    // place a human-readable line may be written.
                    io.github.youndie.kachok.cli.mcp.Mcp
                        .run(Arguments.parseMcp(arguments.drop(1)), System.`in`, frames(out), err)
                } catch (usage: UsageException) {
                    err.appendLine("kachok: ${usage.message}")
                    err.appendLine(Arguments.USAGE)
                    Download.EXIT_USAGE
                }
            }

            null -> {
                err.appendLine(Arguments.USAGE)
                Download.EXIT_USAGE
            }

            else -> {
                err.appendLine("kachok: unknown command '$command'")
                err.appendLine(Arguments.USAGE)
                Download.EXIT_USAGE
            }
        }
    }

    private fun download(
        options: DownloadOptions,
        out: Appendable,
        err: Appendable,
    ): Int =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                Download(options, out, err).run(scope)
            } finally {
                scope.cancel()
            }
        }
}

/**
 * Where JSON-RPC frames go: UTF-8, whatever the platform's own encoding is
 * ([B-137](../../../../../../../docs/backlog/B-137-mcp-frames-are-utf8.md)).
 *
 * `System.out` encodes with `stdout.encoding`, which on Windows is the console's code page, and a
 * frame is UTF-8 by the protocol's definition: every Cyrillic torrent name an agent asked about
 * came back as `???????`. A `PrintStream` passes the bytes written to it through unchanged, so a
 * UTF-8 writer layered over it puts exactly UTF-8 on the descriptor. Anything that is not a print
 * stream — a test's buffer, a pipe a caller already chose — is left as it is.
 */
internal fun frames(out: Appendable): Appendable =
    if (out is java.io.PrintStream) java.io.PrintStream(out, true, Charsets.UTF_8) else out
