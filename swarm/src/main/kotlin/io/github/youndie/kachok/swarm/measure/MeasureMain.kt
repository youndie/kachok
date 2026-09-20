package io.github.youndie.kachok.swarm.measure

/**
 * `./gradlew :swarm:measure -Pscenario=picker-order -Pruns=4`, or
 * `./gradlew :swarm:measure -Pscenario=swarm-order -Pscales=1,3,6` for the contribution curve.
 *
 * **Not part of `build`, and the reason is the one `CollectorBench` already gives:** it takes
 * minutes, it measures this machine as much as this code, and a number produced on a shared CI
 * runner would be worse than no number. What belongs in `build` is [Measure.verify], which cannot
 * report a timing.
 */
public object MeasureMain {
    @JvmStatic
    public fun main(args: Array<String>) {
        val name = argument(args, "--scenario") ?: Scenarios.all.keys.first()
        val runs = argument(args, "--runs")?.toIntOrNull() ?: DEFAULT_RUNS
        val scenario =
            Scenarios.all[name] ?: error(
                "no scenario called '$name'; this repository asks: ${Scenarios.all.keys.joinToString(", ")}",
            )

        val scales = argument(args, "--scales")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        if (scales != null) {
            // The other question this stand can be asked: not "which variant" but "does a client
            // contribute at all, and after how long" (B-127). A curve, so no control and no ratio.
            println("measuring what ${scenario.name}'s clients give each other, at ${scales.joinToString(", ")}x")
            val contribution = Measure.contribution(scenario, scales)
            println()
            print(contribution.format(machine()))
            return
        }

        println("measuring ${scenario.name} — $runs rounds, interleaved, the first of each discarded")
        val report = Measure.compare(scenario, runs)
        println()
        print(report.format(machine()))
    }

    /**
     * The machine and the day, which is half of what an absolute number means.
     *
     * The rule this suppresses is about a time two parties have to agree on, and this is the
     * opposite: nobody reads it back, and a report without a date is a number that will be quoted
     * next year as though it were taken then.
     */
    @Suppress("ktlint:kapkan:wall-clock", "a stamp on a report a person reads, agreed with nobody")
    private fun machine(): String =
        "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
            "${Runtime.getRuntime().availableProcessors()} cores, ${java.time.LocalDate.now()}"

    private fun argument(
        args: Array<String>,
        name: String,
    ): String? {
        val at = args.indexOf(name)
        return if (at >= 0 && at + 1 < args.size) args[at + 1] else null
    }

    private const val DEFAULT_RUNS = 4
}
