package io.github.youndie.kachok.swarm.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every catalogued scenario runs, and no timing crosses into `build`.
 *
 * **A measurement harness that has rotted looks exactly like one that is fine** until somebody runs
 * it, which is months later and usually the day they need the number. What this asserts is that the
 * stand still poses each question — both variants download the whole torrent from the swarm — and
 * nothing about how long that took, because a threshold in seconds on a shared runner is a gate
 * that measures the runner
 * ([B-125](../../../../../../../../docs/backlog/B-125-a-measurement-that-is-a-pair.md)).
 */
class ScenariosTest {
    @Test
    fun everyScenarioStillPosesItsQuestion() {
        Scenarios.all.values.forEach { scenario -> Measure.verify(scenario) }
    }

    /** And the stand is one in which rarity exists, or the question is not the one it claims. */
    @Test
    fun theStandsHavePiecesOfDifferentRarity() {
        Scenarios.all.values.forEach { scenario ->
            val availability =
                (0 until scenario.stand.pieces).map { piece ->
                    scenario.stand.seeds.count { piece in it }
                }
            assertTrue(
                availability.min() >= 1,
                "${scenario.name}: a piece no seed holds makes the download impossible, not rare",
            )
            assertTrue(
                availability.toSet().size > 1,
                "${scenario.name}: every piece is equally available, so the pickers cannot differ",
            )
        }
    }

    /**
     * A comparison of one round is refused rather than published.
     *
     * The same variant has given 1.14 and 2.42 back to back on this repository's own stands; a
     * single run of each is a coin toss with a decimal point on it.
     */
    @Test
    fun aComparisonTooSmallToMeanAnythingIsRefused() {
        val failure =
            runCatching { Measure.compare(Scenarios.pickerOrder, repetitions = 1) }
                .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "one round was accepted as a comparison")
    }

    /** A ratio whose range spans 1.0 is not published as a percentage. */
    @Test
    fun aDifferenceInsideTheNoiseIsPrintedAsNoDifference() {
        val report =
            Report(
                scenario = Scenarios.pickerOrder,
                control = listOf(100L, 120L, 140L),
                variant = listOf(110L, 130L, 150L),
            )
        assertTrue(report.indistinguishable, "the runs overlap; the ratio cannot be called a difference")
        assertTrue(
            report.format("a machine").contains("no difference this stand can see"),
            "a ratio taken from inside the noise was published as a number",
        )
    }

    /** And one that does not span 1.0 is. */
    @Test
    fun aDifferenceLargerThanTheNoiseIsPrintedAsARatio() {
        val report =
            Report(
                scenario = Scenarios.pickerOrder,
                control = listOf(100L, 102L, 104L),
                variant = listOf(200L, 204L, 208L),
            )
        assertTrue(!report.indistinguishable)
        assertEquals(2.0, report.ratio, "the median ratio of doubled times is not 2.0")
        assertTrue(report.format("a machine").contains("sequential / rarest-first = 2.00"))
    }
}
