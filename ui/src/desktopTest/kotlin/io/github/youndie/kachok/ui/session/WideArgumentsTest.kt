package io.github.youndie.kachok.ui.session

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame

/**
 * Which arguments the client believes, when the ones it was handed cannot be trusted.
 *
 * **The syscall is not what a test can hold here**, and pretending otherwise would be the more
 * comfortable mistake: `GetCommandLineW` exists on one of the three platforms this client runs on
 * and this suite runs on the other two. What travels between machines is the *rule* — which entries
 * of the real command line are this application's, and when to leave well alone — and that rule is
 * where a defect would hide, because the syscall either works or throws.
 *
 * The reading that proves the call itself is in
 * [B-116](../../../../../../../../docs/backlog/B-116-a-torrent-whose-name-is-not-ascii-cannot-be-opened-on-windows.md):
 * a packaged launcher, a Cyrillic file name, and the same path arriving as `?` through `argv` and
 * intact through the wide line.
 */
class WideArgumentsTest {
    @Test
    fun theApplicationsArgumentsAreTheTailOfTheRealCommandLine() {
        // What a development run looks like: the JVM's own arguments first, ours last.
        val real =
            listOf(
                "C:\\jdk\\bin\\java.exe",
                "-cp",
                "kachok.jar",
                "io.github.youndie.kachok.ui.AppKt",
                "C:\\Downloads\\Кириллица.torrent",
            )

        val taken = wideArguments(arrayOf("C:\\Downloads\\????????.torrent"), onWindows = true) { real }

        assertContentEquals(arrayOf("C:\\Downloads\\Кириллица.torrent"), taken, "the tail was not taken")
    }

    /** And under a packaged launcher, where the real line is the executable and our arguments. */
    @Test
    fun aPackagedLauncherPassesOnlyWhatTheApplicationWasGiven() {
        val real = listOf("C:\\kachok\\kachok.exe", "C:\\Downloads\\Кириллица.torrent", "--autostart")

        val taken =
            wideArguments(
                arrayOf("C:\\Downloads\\????????.torrent", "--autostart"),
                onWindows = true,
            ) { real }

        assertContentEquals(
            arrayOf("C:\\Downloads\\Кириллица.torrent", "--autostart"),
            taken,
            "a flag beside the path moved the window",
        )
    }

    /** Everywhere else the arguments are already right, and reading anything would be a risk for nothing. */
    @Test
    fun nothingIsReadWhereTheArgumentsAreAlreadyRight() {
        val given = arrayOf("/home/youndie/Загрузки/Кириллица.torrent")

        val taken = wideArguments(given, onWindows = false) { error("the command line was read off Windows") }

        assertSame(given, taken)
    }

    /**
     * A real line shorter than what the JVM reported describes a different launch.
     *
     * Guessing which entry is which would be inventing a mapping; the flattened arguments at least
     * start the client, which is more than this defect left it doing.
     */
    @Test
    fun aLineThatCannotBeTheSameLaunchIsLeftAlone() {
        val given = arrayOf("one.torrent", "two.torrent")

        val taken = wideArguments(given, onWindows = true) { listOf("kachok.exe") }

        assertSame(given, taken)
    }

    /** And a call that throws is a client that still starts. */
    @Test
    fun aFailedReadIsNotAFailedStart() {
        val given = arrayOf("C:\\Downloads\\x.torrent")

        val taken = wideArguments(given, onWindows = true) { throw UnsatisfiedLinkError("no Shell32 here") }

        assertSame(given, taken)
    }
}
