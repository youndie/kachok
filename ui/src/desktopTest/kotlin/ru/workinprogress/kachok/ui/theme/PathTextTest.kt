package ru.workinprogress.kachok.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A directory is identified by its last component, so the front is what goes.
 *
 * `TextOverflow.StartEllipsis` type-checks against Compose Multiplatform 1.12 and truncates at the
 * end anyway, with and without `softWrap = false` — checked twice against a golden before this was
 * written. Hence the measurement, and hence this test: the constant compiling proved nothing, and
 * nothing would have caught the regression back to it.
 */
@OptIn(ExperimentalTestApi::class)
class PathTextTest {
    private val path = "/private/tmp/claude-501/-Users-youndie-Documents-GitHub/scratchpad/live/downloads"

    private fun shown(
        text: String,
        width: Int,
    ): String {
        var drawn = ""
        runComposeUiTest {
            setContent {
                KachokTheme {
                    Box(Modifier.width(width.dp)) {
                        PathText(text, MonoSmall, androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            drawn =
                onRoot()
                    .fetchSemanticsNode()
                    .let { root ->
                        generateSequence(listOf(root)) { nodes ->
                            nodes.flatMap { it.children }.ifEmpty { null }
                        }.flatten()
                            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }
                            .first()
                    }
        }
        return drawn
    }

    @Test
    fun aPathThatDoesNotFitLosesItsFrontAndKeepsItsEnd() {
        val drawn = shown(path, width = 160)
        assertTrue(drawn.startsWith("…"), drawn)
        assertTrue(drawn.endsWith("downloads"), drawn)
        assertTrue(path.endsWith(drawn.removePrefix("…")), "$drawn is not a tail of the path")
        assertTrue(drawn.length < path.length, drawn)
    }

    /** A path that fits is not touched — no ellipsis, nothing dropped. */
    @Test
    fun aPathThatFitsIsLeftAlone() {
        assertEquals("~/Downloads/iso", shown("~/Downloads/iso", width = 300))
    }

    /** Wider means more of it, never less. */
    @Test
    fun aWiderCellShowsMoreOfTheSamePath() {
        val narrow = shown(path, width = 120)
        val wide = shown(path, width = 220)
        assertTrue(wide.length > narrow.length, "'$narrow' then '$wide'")
        assertTrue(wide.endsWith("downloads") && narrow.endsWith("downloads"))
    }
}
