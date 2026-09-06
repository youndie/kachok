package ru.workinprogress.kachok.ui.main

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.session.PeerView
import ru.workinprogress.kachok.engine.session.SessionState
import ru.workinprogress.kachok.ui.details.DetailsState
import ru.workinprogress.kachok.ui.details.DetailsTab
import ru.workinprogress.kachok.ui.session.Rates
import ru.workinprogress.kachok.ui.session.detailsOf

private const val KIB = 1024L
private const val MIB = KIB * KIB
private const val GIB = MIB * KIB

/**
 * The session behind the design's own details panel.
 *
 * Not a hand-written list of the panel's rows: a `SessionState` with the design's numbers in it,
 * put through the same `detailsOf` the running app uses. A fixture of finished strings would prove
 * the panel draws strings; this proves the mapping produces the design's.
 *
 * `left` is set rather than subtracted because BEP 3 says it is not `total - downloaded` after a
 * resume — and because the design's own two numbers do not subtract to its third.
 */
private val designPeers: List<PeerView> =
    listOf(
        designPeer("88.99.242.17:6881", "libtorrent 2.0", down = 1_842, unchoked = true, interested = true),
        designPeer("37.120.185.9:51413", "Transmission 4.0", down = 1_204, unchoked = true, interested = true),
        designPeer("92.61.34.108:6889", "qBittorrent 5.1", down = 918, unchoked = true, interested = true),
        designPeer("185.21.216.4:6881", "kachok 0.1", down = 348, unchoked = true, interested = true),
        designPeer("45.83.220.66:24810", "Deluge 2.1.1", down = 0, unchoked = false, interested = true),
        designPeer("213.152.180.3:6881", "libtorrent 1.2", down = 0, unchoked = false, interested = true),
        designPeer("109.201.152.20:1337", "unknown", down = 0, unchoked = false, interested = false),
        designPeer("5.181.190.7:6892", "BiglyBT 3.7", down = 0, unchoked = false, interested = true),
        designPeer("31.14.40.221:51413", "Transmission 3.0", down = 0, unchoked = false, interested = false),
    )

private fun designPeer(
    address: String,
    client: String,
    down: Long,
    unchoked: Boolean,
    interested: Boolean,
) = PeerView(
    address = address,
    client = client,
    dialled = true,
    choking = !unchoked,
    choked = false,
    interested = interested,
    peerInterested = true,
    fast = false,
    extended = true,
    outstanding = if (unchoked) 8 else 0,
    pieces = 1772,
    downBytesPerSecond = down * KIB,
    upBytesPerSecond = 0,
)

internal val designSession: SessionState =
    SessionState(
        infoHash =
            InfoHash(
                byteArrayOf(
                    0x2B.toByte(),
                    0x3A.toByte(),
                    0x91.toByte(),
                    0xC4.toByte(),
                    0xE0.toByte(),
                    0xF7.toByte(),
                    0xD8.toByte(),
                    0xA5.toByte(),
                    0xB6.toByte(),
                    0xC3.toByte(),
                    0x91.toByte(),
                    0xE2.toByte(),
                    0xF7.toByte(),
                    0x0D.toByte(),
                    0x4A.toByte(),
                    0x8B.toByte(),
                    0x5C.toByte(),
                    0x6D.toByte(),
                    0xC7.toByte(),
                    0xF1.toByte(),
                ),
            ),
        name = "debian-13.1.0-amd64-DVD-1.iso",
        totalLength = 3_972_844_748,
        pieceCount = 1772,
        completedPieces = 1384,
        downloaded = (2.89 * GIB).toLong(),
        uploaded = 412 * MIB,
        left = 828 * MIB,
        connectedPeers = 24,
        unchokedPeers = 4,
        outstandingRequests = 61,
        knownPeers = 187,
        extendedPeers = 19,
        dhtNodes = 214,
        hashFailures = 2,
        verifiedPieces = 1772,
        verifyingOf = 1772,
        trackerError = "announce failed: 502 from http://bttracker.debian.org:6969/announce",
        lastPeerError = "185.21.216.4:51413 — connection reset",
        // The design's own nine, in its own order: the four this client unchoked at the top and
        // the five choking it below. `109.201.152.20` is the one whose id said nothing usable, so
        // the reference draws a dash where its client would be.
        peers = designPeers,
    )

internal fun designDetails(tab: DetailsTab = DetailsTab.Overview): DetailsState =
    detailsOf(
        state = designSession,
        rates = Rates(down = 4_312 * KIB, up = 812 * KIB),
        pieceLength = 2 * MIB,
        directory = "~/Downloads/iso",
        tab = tab,
    )
