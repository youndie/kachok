package io.github.youndie.kachok.engine.storage

import io.github.youndie.kachok.engine.metainfo.Metainfo
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.UserDefinedFileAttributeView

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
) : SpanSink,
    AutoCloseable {
    public fun channel(file: Int): FileChannel = channels[file]

    /**
     * A gathering write, aimed by moving the channel's position.
     *
     * The JDK has `write(ByteBuffer, long)` and `write(ByteBuffer[], int, int)` and **no
     * positional gathering write**, so aiming means `position(…)` — which is channel state, and
     * therefore safe only because exactly one coroutine ever writes (research D4).
     */
    override fun writeSpan(
        file: Int,
        position: Long,
        buffers: Array<ByteBuffer>,
    ) {
        val channel = channels[file]
        channel.position(position)
        var remaining = buffers.sumOf { it.remaining().toLong() }
        while (remaining > 0) {
            val written = channel.write(buffers)
            check(written > 0) { "the channel for file \$file wrote nothing with \$remaining bytes left" }
            remaining -= written
        }
    }

    /**
     * `FileChannel.transferTo`: the kernel's own copy, from the page cache to the socket.
     *
     * Positional, so it does not disturb the channel position the single writer aims with.
     */
    override fun transferSpan(
        file: Int,
        position: Long,
        length: Int,
        target: WritableByteChannel,
    ): Long = channels[file].transferTo(position, length.toLong(), target)

    override fun readSpan(
        file: Int,
        position: Long,
        buffer: ByteBuffer,
    ): Int {
        var at = position
        var total = 0
        while (buffer.hasRemaining()) {
            val read = channels[file].read(buffer, at)
            if (read < 0) return if (total == 0) -1 else total
            at += read
            total += read
        }
        return total
    }

    override fun flushAll() {
        flush()
    }

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
        /** `ZoneId=3` is the internet zone, which is where a swarm is. */
        internal const val MARK_OF_THE_WEB: String = "[ZoneTransfer]\r\nZoneId=3\r\n"

        internal const val ZONE_IDENTIFIER: String = "Zone.Identifier"

        private val WINDOWS: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

        private val OPTIONS =
            setOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE,
            )

        /**
         * Where a torrent's files would go, without creating anything.
         *
         * Shared with [open] rather than repeated beside it: a caller asking "is this path already
         * taken" has to be asking about the paths this class would actually open, and a second copy
         * of the single-file rule below is a second chance to answer about the wrong ones.
         */
        public fun pathsIn(
            root: Path,
            metainfo: Metainfo,
        ): List<Path> {
            // A single-file torrent's `name` IS the file; a multi-file torrent's is the directory
            // its files sit in (BEP 3). Treating the first as the second writes the download one
            // level too deep, into a directory named after the file it should have been.
            val base = if (metainfo.isSingleFile) root else root.resolve(metainfo.name)
            return metainfo.files.map { file -> base.resolve(file.path.joinToString("/")).normalize() }
        }

        /**
         * Opens every file of [metainfo] under [root], creating directories and sparse files as
         * needed. A file that already exists is kept and extended, never truncated: that is how a
         * resumed download finds its data.
         */
        public fun open(
            root: Path,
            metainfo: Metainfo,
        ): FileSet = open(root, metainfo, mark = { markDownloaded(it) })

        /**
         * The same, with the mark as a seam.
         *
         * Only so that *which files are marked* can be asserted where the mark itself cannot be: on
         * Linux and macOS `markDownloaded` is deliberately a no-op, so a test there would pass over
         * a version that marked nothing, everything, or the wrong ones.
         */
        internal fun open(
            root: Path,
            metainfo: Metainfo,
            mark: (Path) -> Unit,
        ): FileSet {
            val paths = pathsIn(root, metainfo)
            paths.forEach { Files.createDirectories(it.parent) }

            paths.forEachIndexed { index, path ->
                // Before the file exists, because that is the only moment this client can tell a
                // file it is creating from one it is resuming into.
                val fresh = Files.notExists(path)
                size(path, metainfo.files[index].length)
                if (fresh) mark(path)
            }
            val channels = paths.map { FileChannel.open(it, OPTIONS) }
            return FileSet(channels, paths)
        }

        /**
         * Marks a file as having come from the internet, the way every browser does.
         *
         * **A file this client writes carries no Mark-of-the-Web unless it is put there**, and that
         * is what makes Windows ask its "unknown publisher" question before running a binary. A
         * torrent client that writes an executable without it and then opens it on a double-click
         * has removed a warning the operating system would otherwise have given — about a binary
         * that came from strangers, which is what a swarm is
         * ([B-93](../../../../../../../../docs/backlog/B-93-opening-a-downloaded-executable.md)).
         *
         * **Everything, not executables.** Browsers mark every download; doing it only for `.exe`
         * is a list of extensions that will be wrong, and the mark is what makes the rest of the
         * system — SmartScreen, Office's protected view, the shell's own prompt — treat the file as
         * what it is.
         *
         * Windows only: the stream is an NTFS alternate data stream, which is exactly what
         * `UserDefinedFileAttributeView` writes there. On Linux and macOS the same view is an
         * extended attribute and `Zone.Identifier` would mean nothing to anything — macOS has its
         * own idea, `com.apple.quarantine`, and it is not this item's.
         *
         * Failing to mark is not failing to download: a filesystem without alternate data streams —
         * FAT32 on a memory stick, a network share — cannot carry it, and a client that refused to
         * write a torrent there would be worse than one that writes it unmarked.
         */
        internal fun markDownloaded(
            path: Path,
            onWindows: Boolean = WINDOWS,
            write: (Path, String) -> Unit = ::writeZoneIdentifier,
        ) {
            if (!onWindows) return
            write(path, MARK_OF_THE_WEB)
        }

        private fun writeZoneIdentifier(
            path: Path,
            mark: String,
        ) {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java) ?: return
            try {
                view.write(ZONE_IDENTIFIER, ByteBuffer.wrap(mark.toByteArray(Charsets.US_ASCII)))
            } catch (unsupported: IOException) {
                // A filesystem that has no streams to write. The download is the point; the mark is
                // what the download deserves where it can be had.
                System.err.println("kachok: cannot mark $path as downloaded: ${unsupported.message}")
            }
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
