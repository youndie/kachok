package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.ui.details.FileRow
import java.awt.Desktop
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Handing a file to whatever the system opens that kind of file with.
 *
 * `Desktop.open` is one call that does it on all three platforms — LaunchServices, `ShellExecute`,
 * `xdg-open` — and it is the class the drop target already comes from, so nothing new is pulled in
 * ([B-85](../../../../../../../../docs/backlog/B-85-open-a-file-from-the-files-tab.md)).
 *
 * **It returns a sentence rather than throwing or succeeding silently.** Every way this can fail is
 * a thing the person who double-clicked can act on — the file is not finished, it was never
 * fetched, the session has no desktop environment, nothing is registered for that type — and a
 * gesture that does nothing at all is indistinguishable from a window that is broken. Null means
 * it opened.
 */
internal fun openFile(
    file: FileRow,
    open: (Path) -> Unit = { Desktop.getDesktop().open(it.toFile()) },
    desktopAvailable: () -> Boolean = ::desktopCanOpen,
): String? {
    val name = file.name.substringAfterLast('/')
    // **An unfinished file is refused, and this is the item's open decision.**
    //
    // The three candidates were: open it anyway, offer the folder instead, or refuse with a reason.
    // Opening it hands a player a file that stops in the middle, and this client cannot know which
    // formats survive that — the ones that do are what sequential download is for
    // ([B-65](../../../../../../../../docs/backlog/B-65-sequential-download.md)), and a person who
    // has turned that on knows what they are doing in a way a double-click cannot express.
    if (!file.wanted) return "$name is skipped — there is nothing on the disk to open."
    if (!file.complete) return "$name is ${file.progress} — opening it would hand a truncated file over."
    val path = file.path ?: return "$name has no path yet: the metainfo has not arrived."
    // `Desktop.isDesktopSupported()` is false on a Linux session with no desktop environment, and
    // `open` there throws `UnsupportedOperationException` rather than doing nothing visible.
    if (!desktopAvailable()) return "This session has no desktop environment to open $name with."
    if (!Files.exists(Path.of(path))) return "$name is not on the disk at $path."
    return try {
        open(Path.of(path))
        null
    } catch (unopenable: IOException) {
        // **The system's own words, and the folder.**
        //
        // This used to say only "nothing is registered for that type", on the reasoning that the
        // exception's message is usually the path back again. That reasoning cost a diagnosis: a
        // report of "it says it cannot find the program" could not be told apart from half a dozen
        // other causes, because the one sentence that would have said which was thrown away. What
        // a person can act on is the folder; what somebody reading a screenshot can act on is the
        // rest.
        "Could not open $name: ${unopenable.message ?: "the system refused and said nothing"}. " +
            "It is in ${Path.of(path).parent}."
    } catch (unsupported: UnsupportedOperationException) {
        "This session cannot open files: ${unsupported.message}"
    }
}

private fun desktopCanOpen(): Boolean =
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
