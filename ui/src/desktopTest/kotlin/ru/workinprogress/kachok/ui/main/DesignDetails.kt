package ru.workinprogress.kachok.ui.main

import ru.workinprogress.kachok.engine.InfoHash
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
internal val designSession: SessionState =
    SessionState(
        infoHash =
            InfoHash(
                ByteArray(20).also {
                    it[0] = 0x2B
                    it[1] = 0x3A
                    it[18] = 0xC7.toByte()
                    it[19] = 0xF1.toByte()
                },
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
    )

internal fun designDetails(tab: DetailsTab = DetailsTab.Overview): DetailsState =
    detailsOf(
        state = designSession,
        rates = Rates(down = 4_312 * KIB, up = 812 * KIB),
        pieceLength = 2 * MIB,
        directory = "~/Downloads/iso",
        tab = tab,
    )
