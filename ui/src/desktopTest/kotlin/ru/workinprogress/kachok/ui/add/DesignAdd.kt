package ru.workinprogress.kachok.ui.add

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.metainfo.MagnetLink
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.metainfo.TorrentFile
import ru.workinprogress.kachok.ui.session.addFrom

private const val KIB = 1024L
private const val MIB = KIB * KIB
private const val GIB = MIB * KIB

private val designFiles =
    listOf(
        "debian-13.1.0-amd64-DVD-1.iso" to (3.61 * GIB).toLong(),
        "SHA512SUMS" to 1_229L,
        "SHA512SUMS.sign" to 833L,
        "MD5SUMS" to 784L,
        "README.source" to 2_150L,
        "dists/stable/Release" to 62_464L,
        "dists/stable/Release.gpg" to 2_458L,
        ".disk/info" to 62L,
        ".disk/mkisofs" to 216L,
    )

/**
 * The design's own torrent, as a `Metainfo` rather than as a list of strings the dialog would echo.
 *
 * Constructed rather than parsed because what is under test is the dialog's arithmetic, not the
 * bencode parser — which has its own tests, and which nothing here would exercise differently.
 */
internal val designMetainfo: Metainfo =
    Metainfo(
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
        pieceLength = (2 * MIB).toInt(),
        totalLength = 3_972_844_748,
        files =
            designFiles.foldIndexed(mutableListOf<TorrentFile>()) { _, acc, (path, length) ->
                acc += TorrentFile(path.split("/"), length, acc.sumOf { it.length })
                acc
            },
        pieceHashes = ByteArray(PIECES * Metainfo.HASH_SIZE),
        trackers = listOf("http://bttracker.debian.org:6969/announce"),
        isPrivate = false,
        isSingleFile = false,
        infoBytes = ByteArray(0),
    )

internal val designTorrentToAdd: AddTorrentState =
    addFrom(
        metainfo = designMetainfo,
        fileName = "debian-13.1.0-amd64-DVD-1.iso.torrent",
        saveTo = "~/Downloads/iso",
        defaultDirectory = "~/Downloads",
    )

internal val designMagnet: MagnetLink =
    MagnetLink(
        infoHash = InfoHash(ByteArray(20) { (0xE4 + it).toByte() }),
        displayName = "archlinux-2026.09.01-x86_64.iso",
        trackers = emptyList(),
    )

internal val designMagnetToAdd: AddTorrentState =
    addFrom(link = designMagnet, saveTo = "~/Downloads/iso", defaultDirectory = "~/Downloads")

private const val PIECES = 1772

/** The same magnet, in a window that already runs a torrent. */
internal val designMagnetRefused: AddTorrentState =
    AddTorrentState(
        source = designMagnetToAdd.source,
        summary = designMagnetToAdd.summary,
        hash = designMagnetToAdd.hash,
        magnet = true,
        saveTo = designMagnetToAdd.saveTo,
        defaultNote = designMagnetToAdd.defaultNote,
        files = emptyList(),
        wantedSummary = "",
        canAdd = false,
        whyNot = "This build runs one torrent at a time.",
    )
