package ru.workinprogress.kachok.engine.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The one dispatcher the engine runs on, and the views taken of it.
 *
 * Every coroutine in the engine — peer readers, the writer, the timer, the tracker loop — runs on
 * a virtual-thread-per-task executor. A coroutine that suspends resumes on some virtual thread of
 * this executor; a blocking socket read inside one parks that virtual thread and leaves its
 * carrier free (verified in `sun/nio/ch/SocketChannelImpl`, research §1.1). Both halves of the
 * design therefore live on the same threads and there is no hand-off between an "I/O pool" and a
 * "coroutine pool" to get wrong.
 *
 * `Dispatchers.IO` and `Dispatchers.Default` do not appear anywhere in engine code, on purpose:
 * they are pools of platform threads, which is the thing this design replaces.
 *
 * **File I/O is the exception the design has to respect.** A blocking file read does *not* unmount
 * a virtual thread — the JDK compensates by adding a carrier instead (`Blocker.begin` in
 * `sun/nio/ch/FileChannelImpl`) — so disk work is confined to one writer coroutine rather than
 * spread across peers (research D4).
 */
public class EngineDispatchers(
    private val executorDispatcher: ExecutorCoroutineDispatcher =
        Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher(),
) : AutoCloseable {
    /** Peer readers and writers, the tracker, the timer: everything that waits on a socket. */
    public val io: CoroutineDispatcher get() = executorDispatcher

    override fun close() {
        executorDispatcher.close()
    }
}
