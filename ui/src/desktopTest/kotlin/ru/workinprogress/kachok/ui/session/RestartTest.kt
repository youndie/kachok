package ru.workinprogress.kachok.ui.session

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ru.workinprogress.kachok.engine.hex
import ru.workinprogress.kachok.engine.metainfo.MetainfoParser
import ru.workinprogress.kachok.ui.Client
import ru.workinprogress.kachok.ui.theme.KachokTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closing the window is not abandoning the torrents
 * ([B-81](../../../../../../../../docs/backlog/B-81-the-torrent-list-survives-a-restart.md)).
 *
 * `StoredTorrentsTest` asserts that the list on the disk is right. This asserts the half that the
 * list exists for: that a *window* opened with nothing on the command line comes up holding what
 * the previous one had. Those are different claims, and the second is the one that was missing —
 * the records were on the disk and good before this item, and nothing read them.
 *
 * No swarm: what is being measured is which rows the window opens with, and a torrent nobody is
 * seeding still has a row. The trackers are `.invalid`, so nothing leaves the machine.
 */
@OptIn(ExperimentalTestApi::class)
class RestartTest {
    private val root: Path = Files.createTempDirectory("kachok-restart")
    private val list: Path get() = root.resolve("torrents")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() = root.deleteRecursively()

    private fun remember(
        name: String,
        paused: Boolean = false,
    ) = MetainfoParser.parse(TestTorrents.bytes(name)).also {
        rememberTorrent(list, it, saveTo = root.toString(), paused = paused)
    }

    /** A window with no argument at all, which is what a person's second start looks like. */
    private fun ComposeUiTest.openWithNothing() =
        setContent {
            KachokTheme {
                Client(
                    initial = null,
                    directory = root,
                    settingsFile = root.resolve("settings.properties"),
                    torrents = list,
                )
            }
        }

    /**
     * `substring`, because half of what is waited for here is a sentence and not a cell.
     *
     * A row's name is its whole text; the banner's is "gamma.bin could not be opened." and matching
     * that exactly would mean writing the wording out twice, in a test whose subject is not the
     * wording.
     */
    private fun ComposeUiTest.await(text: String) =
        waitUntil(timeoutMillis = WAIT) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun aWindowOpenedWithNoArgumentsComesUpWithWhatTheLastOneHad() =
        runComposeUiTest {
            remember("alpha.bin")
            remember("beta.bin")
            openWithNothing()

            await("alpha.bin")
            await("beta.bin")
        }

    /**
     * And the one that was paused is still paused.
     *
     * Not a detail: a torrent that comes back running is one that announces to a swarm and starts
     * pulling bytes on a connection somebody deliberately stopped using.
     */
    @Test
    fun aTorrentThatWasPausedComesBackPaused() =
        runComposeUiTest {
            remember("alpha.bin", paused = true)
            openWithNothing()

            await("alpha.bin")
            await("Paused")
        }

    /**
     * A remembered torrent whose copy has gone is a row and a reason, not a silence.
     *
     * The alternative — leaving it out — is indistinguishable from never having had it, and the
     * only person who could notice is the one who has already lost it.
     */
    @Test
    fun aRememberedTorrentThatCannotBeOpenedSaysSoInsteadOfDisappearing() =
        runComposeUiTest {
            val gone = remember("gamma.bin")
            Files.delete(list.resolve("${gone.infoHash.hex()}.torrent"))
            remember("alpha.bin")
            openWithNothing()

            await("gamma.bin")
            await("could not be opened")
            // The working one is still there: one damaged entry costs its own row and no other.
            await("alpha.bin")
        }

    /**
     * Adding a torrent is what puts it on the list, and the command line goes through the same door.
     *
     * Written after the session opens rather than before: `TorrentSet.add` refuses a torrent whose
     * files another one already owns, and a list that remembered the refusal would reopen the
     * collision on every start for ever.
     */
    @Test
    fun aTorrentNamedOnTheCommandLineIsRememberedForNextTime() =
        runComposeUiTest {
            val file = root.resolve("delta.torrent")
            Files.write(file, TestTorrents.bytes("delta.bin"))
            setContent {
                KachokTheme {
                    Client(
                        initial = file,
                        directory = root,
                        settingsFile = root.resolve("settings.properties"),
                        torrents = list,
                    )
                }
            }
            await("delta.bin")
            waitUntil(timeoutMillis = WAIT) { loadStoredTorrents(list).isNotEmpty() }

            val stored = loadStoredTorrents(list).single()
            assertEquals("delta.bin", stored.name)
            assertTrue(stored.metainfo != null, "the copy did not come back: ${stored.problem}")
        }

    /**
     * The dialog that deletes files names the folder the files are in.
     *
     * It named `preferences.directory` — the settings' default — for every torrent, including the
     * ones that are somewhere else, which since [B-81] is any torrent added through *Browse…* or
     * restored from the list. A checkbox that deletes data, beside a folder the data is not in, is
     * asking somebody to agree to something other than what will happen. Found by using the
     * application on Windows, and it is the same defect as the *Save to* field in
     * [B-85](../../../../../../../../docs/backlog/B-85-open-a-file-from-the-files-tab.md) — fixed
     * there as an instance rather than as a class.
     */
    @Test
    fun theRemoveDialogNamesTheTorrentsOwnFolderAndNotTheDefault() =
        runComposeUiTest {
            val elsewhere = Files.createDirectory(root.resolve("elsewhere"))
            val metainfo = MetainfoParser.parse(TestTorrents.bytes("alpha.bin"))
            rememberTorrent(list, metainfo, saveTo = elsewhere.toString())
            openWithNothing()
            await("alpha.bin")

            onNodeWithContentDescription("Remove…").performClick()
            waitUntil(timeoutMillis = WAIT) {
                onAllNodesWithText(elsewhere.toString(), substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                0,
                onAllNodesWithText(root.toString() + java.io.File.separator + "alpha", substring = true)
                    .fetchSemanticsNodes()
                    .size,
                "the dialog named a folder this torrent is not in",
            )
        }

    private companion object {
        const val WAIT = 60_000L
    }
}
