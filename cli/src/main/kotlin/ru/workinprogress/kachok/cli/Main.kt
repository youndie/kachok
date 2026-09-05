package ru.workinprogress.kachok.cli

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
