package io.github.youndie.kachok.control

import io.github.youndie.kachok.engine.hex
import io.github.youndie.kachok.engine.runtime.TorrentSet
import io.github.youndie.kachok.engine.session.SessionState
import io.github.youndie.kachok.wire.FileState
import io.github.youndie.kachok.wire.PeerState
import io.github.youndie.kachok.wire.Snapshot
import io.github.youndie.kachok.wire.TorrentState
import io.github.youndie.kachok.wire.TrackerState

/**
 * The whole set as one payload, numbered by the caller.
 *
 * Shared by the socket and the MCP resource (B-108) so that an agent reading `kachok://snapshot`
 * gets byte-for-byte what a browser page gets — one shape of the truth, not two that drift. That
 * is also why it is here and not in either surface: after
 * [B-117](../../../../../../../../../docs/backlog/B-117-one-client-for-the-window-and-the-agent.md)
 * the MCP server may be answering from inside the window, and a second copy of this function is
 * how the agent's figures and the person's begin to disagree.
 */
public fun TorrentSet.snapshot(sequence: Long): Snapshot =
    Snapshot(
        torrents = torrents.map { it.state.value.onTheWire() },
        listenPort = listenPort,
        dhtNodes =
            if (dhtEnabled) {
                torrents
                    .firstOrNull()
                    ?.state
                    ?.value
                    ?.dhtNodes ?: 0
            } else {
                null
            },
        sequence = sequence,
    )

/**
 * The engine's state as the wire's.
 *
 * Not a mechanical copy: the rates a surface draws are per peer in the engine and summed here,
 * because a client that summed them would be a second implementation of a figure and this one
 * already exists in the desktop window.
 */
public fun SessionState.onTheWire(): TorrentState =
    TorrentState(
        infoHash = infoHash.hex(),
        name = name,
        totalLength = totalLength,
        pieceCount = pieceCount,
        completedPieces = completedPieces,
        downloaded = downloaded,
        uploaded = uploaded,
        left = left,
        connectedPeers = connectedPeers,
        unchokedPeers = unchokedPeers,
        outstandingRequests = outstandingRequests,
        knownPeers = knownPeers,
        hashFailures = hashFailures,
        verifiedPieces = verifiedPieces,
        verifyingOf = verifyingOf,
        downBytesPerSecond = peers.sumOf { it.downBytesPerSecond },
        upBytesPerSecond = peers.sumOf { it.upBytesPerSecond },
        paused = paused,
        isComplete = isComplete,
        trackerError = trackerError,
        lastPeerError = lastPeerError,
        sessionError = sessionError,
        files = files.map { FileState(it.path, it.length, it.verifiedBytes, it.wanted, it.priority.name.lowercase()) },
        peers =
            peers.map {
                PeerState(it.address, it.client, it.choking, it.interested, it.downBytesPerSecond)
            },
        trackers = trackers.map { TrackerState(it.url, it.status.name, it.message) },
    )
