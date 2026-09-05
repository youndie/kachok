package ru.workinprogress.kachok.cli

import kotlin.system.exitProcess

/**
 * The headless entry point. Phase 1 skeleton: the commands are documented in
 * docs/features/feature-cli.md and arrive with the backlog items that implement them.
 */
fun main(args: Array<String>) {
    System.err.println("kachok: no commands yet (skeleton). Arguments: ${args.joinToString(" ")}")
    exitProcess(USAGE)
}

private const val USAGE = 2
