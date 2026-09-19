package io.github.youndie.kachok.swarm.measure

/**
 * What a comparison is allowed to say.
 *
 * **A ratio, with the range it could be, and never a lonely absolute.** The absolutes are printed
 * as context — they belong to this machine on this day and will not match tomorrow's — while the
 * result is `variant / control`, taken from two sets of runs made beside each other. A range that
 * contains 1.0 is printed as "no difference this stand can see", because a percentage taken from
 * inside the noise is a number that will be quoted for a year
 * ([B-125](../../../../../../../../docs/backlog/B-125-a-measurement-that-is-a-pair.md)).
 */
public class Report(
    public val scenario: Scenario,
    public val control: List<Long>,
    public val variant: List<Long>,
) {
    public val controlMedian: Long = median(control)
    public val variantMedian: Long = median(variant)

    /** The middle estimate: median against median. */
    public val ratio: Double = variantMedian.toDouble() / controlMedian

    /**
     * The widest ratio the runs allow, from the two extremes.
     *
     * Deliberately pessimistic rather than statistical: the fastest variant against the slowest
     * control, and the other way round. There is no distribution to assume with three runs, and a
     * confidence interval drawn from three numbers would be arithmetic pretending to be evidence.
     */
    public val lowest: Double = variant.min().toDouble() / control.max()
    public val highest: Double = variant.max().toDouble() / control.min()

    /** True when the runs cannot tell the two variants apart. */
    public val indistinguishable: Boolean get() = lowest <= 1.0 && highest >= 1.0

    public fun format(machine: String): String =
        buildString {
            appendLine("${scenario.name}: ${scenario.what}")
            appendLine(
                "  stand    ${scenario.stand.pieces} pieces of ${scenario.stand.pieceLength} B, " +
                    "${scenario.stand.seeds.size} seeds, ${scenario.stand.bytesPerSecond} B/s each",
            )
            appendLine("  runs     ${control.size} kept of ${control.size + 1}, interleaved")
            appendLine(
                "  ${scenario.control.name.padEnd(PAD)} ${control.joinToString(" ") { "${it}ms" }}" +
                    "  median ${controlMedian}ms",
            )
            appendLine(
                "  ${scenario.variant.name.padEnd(PAD)} ${variant.joinToString(" ") { "${it}ms" }}" +
                    "  median ${variantMedian}ms",
            )
            appendLine()
            if (indistinguishable) {
                appendLine(
                    "  no difference this stand can see: the ratio is somewhere in " +
                        "${format(lowest)}..${format(highest)}, which contains 1.00",
                )
            } else {
                appendLine(
                    "  ${scenario.variant.name} / ${scenario.control.name} = ${format(ratio)} " +
                        "(${format(lowest)}..${format(highest)})",
                )
            }
            appendLine("  on $machine — the absolutes belong to this machine, the ratio is the result")
        }

    private fun format(value: Double): String {
        val scaled = kotlin.math.round(value * SCALE).toLong()
        return "${scaled / SCALE}.${(scaled % SCALE).toString().padStart(2, '0')}"
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val PAD = 14
        const val SCALE = 100L
    }
}
