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

    /**
     * Rarity has to be possible on every stand, and there are two ways to get it.
     *
     * **Statically**, by giving the seeds different subsets — which is what a stand of seeds and one
     * downloader has to do, because nothing about it changes while it runs. **Dynamically**, by
     * having more than one client: four leechers against one seed hold different pieces from the
     * first second onwards, which is what a swarm in its first minute *is*. A stand with neither is
     * one where every piece is equally available for the whole run and no picker can differ from
     * another on it — a comparison that will confidently report nothing.
     */
    @Test
    fun everyStandCanMakeAPieceRare() {
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
                availability.toSet().size > 1 || scenario.stand.leechers > 1,
                "${scenario.name}: one downloader and seeds that all hold the same pieces is a stand " +
                    "on which availability never varies, so no two pickers can differ on it",
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

    /**
     * A run in which nobody traded is called out rather than reported as a ratio.
     *
     * The first `swarm-order` stand was four seconds long — shorter than the choker's ten-second
     * pass, so no peer was ever unchoked by any other, every client took its whole copy from the
     * seed, and both variants "finished at the same time" because both were waiting on the same
     * uplink. It reported a confident 1.00. The number was real and the question was not asked
     * ([B-126](../../../../../../../../docs/backlog/B-126-a-stand-with-more-than-one-leecher.md)).
     */
    @Test
    fun aRunInWhichNobodyTradedIsNotReportedAsAComparison() {
        val everything = 2560
        val report =
            Report(
                scenario = Scenarios.swarmOrder,
                control = listOf(4000L, 4001L, 3999L),
                variant = listOf(4000L, 4002L, 3998L),
                controlFromSeed = listOf(everything, everything, everything),
                variantFromSeed = listOf(everything, everything, everything),
                everythingFromTheSeed = everything,
            )
        assertTrue(report.tradedNothing, "four clients that took four whole copies did trade, apparently")
        assertTrue(
            report.format("a machine").contains("This stand posed no question"),
            "a stand with no trading in it published a ratio as though it had measured one",
        )
    }

    /** And one where a side did trade is not called vacuous just because the other did not. */
    @Test
    fun aSideThatTradedIsTheAnswerRatherThanAFault() {
        val everything = 2560
        val report =
            Report(
                scenario = Scenarios.swarmOrder,
                control = listOf(39250L, 39250L, 39256L),
                variant = listOf(39997L, 39993L, 40003L),
                controlFromSeed = listOf(2512, 2513, 2512),
                variantFromSeed = listOf(everything, everything, everything),
                everythingFromTheSeed = everything,
            )
        assertTrue(
            !report.tradedNothing,
            "one order trading and the other not is the result B-65 predicted, not a broken stand",
        )
        assertTrue(report.format("a machine").contains("sequential / rarest-first = 1.02"))
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
