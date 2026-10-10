package io.github.youndie.kachok.cli.mcp

import io.github.youndie.kachok.cli.Cli
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * A torrent named in Cyrillic comes back named in Cyrillic, on a stdout that is not UTF-8
 * ([B-137](../../../../../../../../docs/backlog/B-137-mcp-frames-are-utf8.md)).
 *
 * `main` hands the command `System.out`, which on Windows encodes with the console's code page. The
 * test gives `Cli.run` exactly that — a print stream in windows-1252 — and reads the bytes back as
 * what an MCP client reads them as, UTF-8. Before the fix the name arrived as `???????`.
 */
class FramesAreUtf8Test {
    private val root: Path = Files.createTempDirectory("kachok-utf8")

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** One piece, one file, named the way the reported torrent was. */
    private fun torrent(): Path {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(PIECE.toLong()),
                    BString("name") to BString(NAME),
                    BString("piece length") to BInteger(PIECE.toLong()),
                    BString("pieces") to BString(ByteArray(HASH) { it.toByte() }),
                ),
            )
        val bytes =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("announce") to BString("http://127.0.0.1:1/annc"),
                        BString("info") to info,
                    ),
                ),
            )
        return root.resolve("cyrillic.torrent").also { Files.write(it, bytes) }
    }

    @Test
    fun aCyrillicNameSurvivesAStdoutInTheConsolesCodePage() {
        val frames =
            listOf(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"add_torrent","arguments":""" +
                    """{"source":${JsonPrimitive(
                        torrent().toString(),
                    )},"directory":${JsonPrimitive(root.toString())}}}}""",
            ).joinToString("\n", postfix = "\n")
        val captured = ByteArrayOutputStream()
        val consoleCodePage = PrintStream(captured, true, Charset.forName("windows-1252"))
        val stdin = System.`in`
        try {
            System.setIn(ByteArrayInputStream(frames.toByteArray(Charsets.UTF_8)))
            Cli.run(
                listOf("mcp", "--standalone", "--dir", root.toString(), "--no-dht"),
                consoleCodePage,
                StringBuilder(),
            )
        } finally {
            System.setIn(stdin)
        }

        val read = captured.toString(Charsets.UTF_8)
        assertContains(read, NAME, message = "the name did not come back intact: $read")
        assertFalse("?????" in read, "the name was replaced with question marks: $read")
    }

    private companion object {
        const val NAME = "Матрица.1999.mkv"
        const val PIECE = 16_384
        const val HASH = 20
    }
}
