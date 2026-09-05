package ru.workinprogress.kachok.ui.session

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Ask for a directory, with the platform's own chooser.
 *
 * **Two implementations, because there is no one dialog that picks a folder on both.** macOS has a
 * real directory chooser behind `apple.awt.fileDialogForDirectories`, an AWT property that turns
 * `FileDialog` into `NSOpenPanel` with `canChooseDirectories` — and does nothing at all anywhere
 * else, where `FileDialog` will only ever return a file. Everywhere else `JFileChooser` in
 * `DIRECTORIES_ONLY` is the one that exists.
 *
 * Written down because the wrong half of this is invisible: on Windows the mac path does not throw,
 * it simply opens a *file* chooser, and a person picking `Downloads` gets nothing back.
 */
internal fun chooseDirectory(
    title: String,
    startingAt: String,
): String? = if (isMac) macDirectory(title, startingAt) else swingDirectory(title, startingAt)

private val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

private fun macDirectory(
    title: String,
    startingAt: String,
): String? {
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    System.setProperty(key, "true")
    return try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.directory = startingAt
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).absolutePath }
    } finally {
        // Restored rather than left set: the same property makes every *file* chooser in this
        // process refuse files, and the add dialog opens one two clicks later.
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}

private fun swingDirectory(
    title: String,
    startingAt: String,
): String? {
    // The system look and feel, so a Windows user gets the Windows dialog rather than Metal.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        .onFailure { return@onFailure }
    val chooser =
        JFileChooser(startingAt).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath
    } else {
        null
    }
}
