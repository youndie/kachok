package io.github.youndie.kachok.swarm.measure

import io.github.youndie.kachok.engine.runtime.RuntimeOptions
import java.nio.file.Path
import kotlin.random.Random

/**
 * The swarm a scenario is run against: how big the torrent is, who holds what, and how fast.
 *
 * **The holdings are the whole point.** On a stand where every seed has everything, no piece is
 * rarer than any other and every picker makes the same requests in a different order, so a figure
 * taken from it measures nothing ([B-65](../../../../../../../../docs/backlog/B-65-sequential-download.md)).
 * And the rate is what makes a timing a timing *of the client*: at loopback speed a download
 * measures the kernel and the disk, and every variant wins by the same amount.
 */
public class Stand(
    public val pieces: Int,
    public val pieceLength: Int,
    /** One entry per seed, naming the pieces it holds. */
    public val seeds: List<Set<Int>>,
    /** Bytes a second per seed. Zero would make the timing the loopback's. */
    public val bytesPerSecond: Long,
    /**
     * How many clients download at once, sharing what they get.
     *
     * **One is a different question from four**, and the difference is the whole of
     * [B-126](../../../../../../../../docs/backlog/B-126-a-stand-with-more-than-one-leecher.md).
     * What a picker costs its *own* download is measurable with one client; what it costs the swarm
     * — [B-65](../../../../../../../../docs/backlog/B-65-sequential-download.md)'s claim that every
     * peer asking for piece 0 first leaves nobody anything rare to trade — is a cost to the peers it
     * trades with, and a stand with one downloader has none.
     */
    public val leechers: Int = 1,
) {
    public val size: Int get() = pieces * pieceLength

    /**
     * The same stand, small enough for `build`.
     *
     * **A scenario is sized for the question, and the question can need time.** `swarm-order` has
     * to run longer than the choker's ten-second pass or nothing is ever unchoked and no client
     * trades with any other — which is a stand that quietly poses no question at all, and the first
     * version of it did exactly that. What `build` needs from a scenario is different and much
     * cheaper: that the stand still *works* — every client finishes, every byte matches — which a
     * fraction of the torrent shows just as well. Whether a run posed its question is checked where
     * it matters, on the real stand, by [Measure.compare] itself.
     */
    public fun scaledDown(): Stand =
        Stand(
            pieces = maxOf(pieces / SCALE, MINIMUM_PIECES),
            pieceLength = pieceLength,
            seeds = seeds.map { held -> held.filterTo(mutableSetOf()) { it < maxOf(pieces / SCALE, MINIMUM_PIECES) } },
            bytesPerSecond = bytesPerSecond,
            leechers = leechers,
        )

    public companion object {
        /**
         * One seed with the torrent and [others] holding a fixed half each: availability runs from
         * one to `others + 1` across the pieces, which is a swarm in which rarity exists.
         *
         * Fixed by a seed rather than drawn fresh, because two runs of the same scenario have to be
         * the same question. A stand that reshuffled between the control and the variant would be
         * measuring the shuffle.
         */
        public fun mixedRarity(
            pieces: Int,
            pieceLength: Int,
            others: Int,
            bytesPerSecond: Long,
            random: Random = Random(SHAPE_SEED),
        ): Stand {
            val everything = (0 until pieces).toSet()
            val halves = List(others) { everything.filterTo(mutableSetOf()) { random.nextBoolean() } }
            return Stand(pieces, pieceLength, listOf(everything) + halves, bytesPerSecond)
        }

        private const val SHAPE_SEED = 42
        private const val SCALE = 8
        private const val MINIMUM_PIECES = 8
    }
}

/** One of the two things being compared: a name, and the options a client is built with. */
public class Variant(
    public val name: String,
    public val options: (Path) -> RuntimeOptions,
)

/**
 * A question, as a value: one stand and two clients that differ in one thing.
 *
 * **Two and not one.** A lonely number does not survive the week it was taken in, let alone the
 * machine; the only thing this stand can say honestly is how one variant compares with another
 * measured beside it, in the same session, on the same swarm.
 */
public class Scenario(
    public val name: String,
    public val what: String,
    public val stand: Stand,
    public val control: Variant,
    public val variant: Variant,
)

/**
 * The scenarios this repository can ask, by name.
 *
 * Small on purpose: a catalogue is a list of questions somebody has, and a question nobody asked is
 * a stand nobody maintains.
 */
public object Scenarios {
    /**
     * What asking in order costs, on a swarm where rarity exists.
     *
     * The figure [B-65](../../../../../../../../docs/backlog/B-65-sequential-download.md) refused to
     * invent and [B-121](../../../../../../../../docs/backlog/B-121-sequential-does-not-serve-a-player.md)
     * could not take. Note what the shape means: one seed holds the torrent and four hold half of it
     * each, so the pieces only the first seed has are served by one peer whatever order they are
     * asked in. A stand is not the swarm; what this compares is two orders against *this* swarm,
     * and the report says so.
     */
    public val pickerOrder: Scenario =
        Scenario(
            name = "picker-order",
            what = "asking for pieces in order, against rarest first",
            stand =
                Stand.mixedRarity(
                    pieces = PIECES,
                    pieceLength = PIECE_LENGTH,
                    others = OTHER_SEEDS,
                    bytesPerSecond = RATE,
                ),
            control = Variant("rarest-first") { RuntimeOptions(directory = it) },
            variant = Variant("sequential") { RuntimeOptions(directory = it, sequential = true) },
        )

    /**
     * **The positive control, and the catalogue is not honest without one.**
     *
     * A comparison that reports "no difference" is worth nothing until something has shown that
     * this harness can see a difference at all — a stand that cannot distinguish anything reports
     * exactly the same sentence as a stand on which two variants really are the same. So one
     * scenario compares a client against itself with its download limited to half of what the
     * swarm will give it, where the answer is arithmetic rather than opinion: it must take about
     * twice as long. If this one stops saying ~2, no null result from the others may be believed.
     */
    public val halfTheRate: Scenario =
        Scenario(
            name = "half-the-rate",
            what = "a client held to half the swarm's rate, against one that is not — the positive control",
            stand =
                Stand.mixedRarity(
                    pieces = PIECES,
                    pieceLength = PIECE_LENGTH,
                    others = OTHER_SEEDS,
                    bytesPerSecond = RATE,
                ),
            control = Variant("unlimited") { RuntimeOptions(directory = it) },
            variant =
                Variant("half") {
                    RuntimeOptions(
                        directory = it,
                        // Half of what the seeds between them will serve.
                        downloadLimitBytesPerSecond = RATE * (OTHER_SEEDS + 1) / 2,
                    )
                },
        )

    /**
     * B-65's actual claim, on a stand that can hold it: four clients, one seed, and not enough seed
     * to go round.
     *
     * The seed is limited to roughly what **one** client would take, so the other three clients'
     * bytes have to come from each other or not at all — which is a swarm in its first minute, and
     * the only shape in which "nobody has anything rare to trade" means anything. The measurement
     * is the **makespan**: when the last of the four finished, because a picker that serves its own
     * client at the swarm's expense is exactly a picker whose own time looks fine.
     */
    public val swarmOrder: Scenario =
        Scenario(
            name = "swarm-order",
            what = "four clients asking in order, against four asking rarest-first",
            stand =
                Stand(
                    pieces = SWARM_PIECES,
                    pieceLength = SWARM_PIECE_LENGTH,
                    seeds = listOf((0 until SWARM_PIECES).toSet()),
                    bytesPerSecond = SWARM_RATE,
                    leechers = LEECHERS,
                ),
            control = Variant("rarest-first") { RuntimeOptions(directory = it) },
            variant = Variant("sequential") { RuntimeOptions(directory = it, sequential = true) },
        )

    public val all: Map<String, Scenario> =
        listOf(pickerOrder, halfTheRate, swarmOrder).associateBy { it.name }

    private const val PIECES = 128
    private const val PIECE_LENGTH = 16 * 1024
    private const val OTHER_SEEDS = 4
    private const val RATE = 256L * 1024

    /**
     * Ten mebibytes in 64 KiB pieces, which is a run of tens of seconds and is meant to be.
     *
     * **The first version of this stand was four seconds long and measured nothing.** Connections
     * start choked and the choke pass runs every ten seconds, so a download that finishes in four
     * never has one: every client took its whole copy from the seed, the four of them together took
     * exactly four copies, and both orders "finished at the same time" because both were waiting on
     * the same uplink. The stand has to outlast the mechanism that lets peers trade, or it is a
     * stand with no trading in it.
     */
    private const val SWARM_PIECES = 160
    private const val SWARM_PIECE_LENGTH = 64 * 1024
    private const val LEECHERS = 4

    /**
     * One seed at roughly one client's worth of bandwidth.
     *
     * Not a number chosen for a shape: it is the condition the scenario needs to exist. A seed that
     * can serve four clients at once is a stand on which nobody has to trade, which is the stand
     * [B-125](../../../../../../../../docs/backlog/B-125-a-measurement-that-is-a-pair.md) already
     * had.
     */
    private const val SWARM_RATE = 1024L * 1024
}
