package ru.workinprogress.kachok.engine.storage

import ru.workinprogress.kachok.engine.metainfo.Metainfo
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The torrent's files on disk, open and ready to be written at any position.
 *
 * **Files are created sparse and never preallocated.** Writing the whole torrent as zeros before
 * downloading it costs exactly as much I/O as downloading it, and buys a guarantee against a
 * disk-full failure this client would rather report when it happens.
 *
 * **A file is sized with `setLength`, and the mechanism is not interchangeable.** Extending a file
 * by writing one byte past its end does *not* leave a hole: measured on APFS, a 4 MB file made
 * that way occupies 3 908 KB — exactly as much as one written out in full — while `setLength` on
 * the same file occupies zero (research §1.3a). The obvious trick is the preallocation this class
 * exists to avoid; only the boring call does the right thing.
 *
 * `StandardOpenOption.SPARSE` is passed and is ignored on Unix — `sun/nio/fs/UnixChannelFactory`
 * drops it outright (research §1.1). It is here for Windows, which phase 1 does not test.
 *
 * Channels are opened once and kept: a torrent is written at random positions for hours, and
 * reopening a file per block would be a system call per block on the hot path.
 */
public class FileSet private constructor(
    private val channels: List<FileChannel>,
    public val paths: List<Path>,
) : AutoCloseable {
    public fun channel(file: Int): FileChannel = channels[file]

    /** `force(false)` on every file: metadata is not worth the extra seek per call. */
    public fun flush() {
        channels.forEach { if (it.isOpen) it.force(false) }
    }

    override fun close() {
        channels.forEach { channel ->
            try {
                channel.close()
            } catch (ignored: java.io.IOException) {
                // A channel that fails to close is already unusable and the data is either on the
                // disk or was never going to be.
            }
        }
    }

    public companion object {
        private val OPTIONS =
            setOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE,
            )

        /**
         * Opens every file of [metainfo] under [root], creating directories and sparse files as
         * needed. A file that already exists is kept and extended, never truncated: that is how a
         * resumed download finds its data.
         */
        public fun open(
            root: Path,
            metainfo: Metainfo,
        ): FileSet {
            val paths =
                metainfo.files.map { file ->
                    root.resolve(metainfo.name).resolve(file.path.joinToString("/")).normalize()
                }
            paths.forEach { Files.createDirectories(it.parent) }

            paths.forEachIndexed { index, path -> size(path, metainfo.files[index].length) }
            val channels = paths.map { FileChannel.open(it, OPTIONS) }
            return FileSet(channels, paths)
        }

        /**
         * Gives a file its declared length without writing its contents.
         *
         * `RandomAccessFile.setLength` and not a positional write past the end: the two look
         * interchangeable and are not. See the class comment for the measurement.
         */
        private fun size(
            path: Path,
            declared: Long,
        ) {
            if (declared <= 0) return
            RandomAccessFile(path.toFile(), "rw").use { file ->
                if (file.length() < declared) file.setLength(declared)
            }
        }
    }
}
