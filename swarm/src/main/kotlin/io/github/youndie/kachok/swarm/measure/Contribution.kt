package io.github.youndie.kachok.swarm.measure

/** One run of one size: how long the clients were in the swarm, and what they gave each other. */
public class ContributionPoint(
    public val bytes: Long,
    public val millis: Long,
    /** Per client, bytes served to the other clients. */
    public val gave: List<Long>,
    /** Per client, bytes verified — which on this stand is the whole torrent, once. */
    public val took: List<Long>,
    /** What the seed had to serve, in bytes. */
    public val fromSeed: Long,
) {
    /** What the swarm gave itself, against what it took: 0.0 is a swarm of free riders. */
    public val ratio: Double get() = if (took.sum() == 0L) 0.0 else gave.sum().toDouble() / took.sum()

    /** Tenths of a second: the stand's own resolution, and more would be pretending. */
    public val seconds: String get() = "${millis / MILLIS_PER_SECOND}.${millis % MILLIS_PER_SECOND / TENTH}"

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val TENTH = 100L
    }
}

/**
 * Whether a client contributes anything, and after how long.
 *
 * **A curve and not a comparison**, which is why it is not a [Report]: there is no control to
 * measure against, because the question has an absolute answer. A swarm in which every byte comes
 * from the seed is a swarm of free riders however fast it finishes
 * ([B-127](../../../../../../../../docs/backlog/B-127-trading-barely-starts-before-a-download-ends.md)).
 */
public class Contribution(
    public val scenario: Scenario,
    public val points: List<ContributionPoint>,
) {
    public fun format(machine: String): String =
        buildString {
            appendLine("${scenario.name}: what ${scenario.stand.leechers} clients gave each other")
            appendLine(
                "  stand    ${scenario.stand.pieceLength} B pieces, ${scenario.stand.seeds.size} seed(s), " +
                    "${scenario.stand.bytesPerSecond} B/s shared, ${scenario.stand.leechers} clients",
            )
            appendLine()
            appendLine(
                "  ${"torrent".padEnd(PAD)}${"in the swarm".padEnd(PAD)}${"gave / took".padEnd(PAD)}from the seed",
            )
            points.forEach { point ->
                appendLine(
                    "  ${mib(point.bytes).padEnd(PAD)}" +
                        "${"${point.seconds} s".padEnd(PAD)}" +
                        "${percent(point.ratio).padEnd(PAD)}" +
                        "${mib(point.fromSeed)} of ${mib(point.took.sum())} taken",
                )
            }
            appendLine()
            appendLine("  on $machine — the absolutes belong to this machine")
        }

    private fun mib(bytes: Long): String = "${bytes * TENTHS / MIB / TENTHS}.${bytes * TENTHS / MIB % TENTHS} MiB"

    private fun percent(value: Double): String {
        val tenths = kotlin.math.round(value * PERCENT * TENTHS).toLong()
        return "${tenths / TENTHS}.${tenths % TENTHS} %"
    }

    private companion object {
        const val PAD = 16
        const val MIB = 1024L * 1024
        const val TENTHS = 10L
        const val PERCENT = 100L
    }
}
