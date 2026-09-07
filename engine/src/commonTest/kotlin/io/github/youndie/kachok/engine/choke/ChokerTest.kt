package io.github.youndie.kachok.engine.choke

import io.github.youndie.kachok.engine.peer.PeerAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The acceptance criteria of B-21, against BEP 3's *choking and optimistic unchoking*. */
class ChokerTest {
    private fun peer(index: Int) = PeerAddress("10.0.0.$index", 6881)

    /** Eight interested peers whose download rates descend with their index. */
    private fun eightPeers() =
        (1..8).map { index ->
            PeerRates(peer(index), interested = true, downloadRate = (9 - index) * 1000L, uploadRate = 0)
        }

    @Test
    fun theFourBestInterestedPeersAreUnchokedPlusOneOptimistic() {
        // Seeded so the optimistic choice is fixed and the ranking is what the test is about.
        val choker = Choker(random = Random(4))
        val decision = choker.pass(eightPeers(), seeding = false, rotateOptimistic = true)

        val optimistic = assertNotNull(decision.optimistic)
        assertTrue(optimistic in decision.unchoked, "the optimistic peer is unchoked by definition")
        assertEquals(4, decision.unchoked.size, "an interested optimistic peer takes one of the four")

        // Every unchoked peer other than the optimistic one is among the fastest.
        val ranked = eightPeers().sortedByDescending { it.downloadRate }.map { it.peer }
        val regular = decision.unchoked - optimistic
        val cutoff = ranked.take(4 + 1)
        assertTrue(
            regular.all { it in cutoff },
            "unchoked $regular but the fastest are ${ranked.take(5)}",
        )
    }

    @Test
    fun anUninterestedOptimisticPeerDoesNotTakeASlot() {
        // BEP 3 puts it in a parenthesis: the optimistic peer counts as one of the four "if
        // interested". A peer that wants nothing is being given a chance, not a slot.
        val peers =
            listOf(PeerRates(peer(0), interested = false, downloadRate = 0, uploadRate = 0)) +
                eightPeers()
        val choker = Choker(random = Random(0))
        var decision = choker.pass(peers, seeding = false, rotateOptimistic = true)
        while (decision.optimistic != peer(0)) {
            decision = Choker(random = Random(Random.nextInt())).pass(peers, false, rotateOptimistic = true)
        }
        assertEquals(5, decision.unchoked.size, "four interested peers plus the uninterested optimistic one")
    }

    @Test
    fun theOptimisticPeerIsKeptUntilItIsDueToRotate() {
        val choker = Choker(random = Random(11))
        val peers = eightPeers()
        val first = choker.pass(peers, seeding = false, rotateOptimistic = true).optimistic
        repeat(3) {
            assertEquals(
                first,
                choker.pass(peers, seeding = false, rotateOptimistic = false).optimistic,
                "a peer given a chance needs long enough to show what it can do",
            )
        }
    }

    @Test
    fun theOptimisticPeerChangesWhenItIsDue() {
        // Over many rotations the choice must move; a "rotation" that always picks the same peer
        // is not a rotation, and the test that only checks one rotation would not notice.
        val choker = Choker(random = Random(3))
        val peers = eightPeers()
        val seen = (1..40).map { choker.pass(peers, seeding = false, rotateOptimistic = true).optimistic }.toSet()
        assertTrue(seen.size > 1, "forty rotations all chose $seen")
    }

    @Test
    fun aSeedRanksByWhatItCanPushAndALeecherByWhatItReceives() {
        // The invariant rather than a fixed answer: the optimistic peer is chosen at random, so a
        // test that names the winner is really testing the random seed. What must hold is that no
        // choked peer beats an unchoked one on the metric in force — download rate while we still
        // want something, upload rate once we have everything (BEP 3).
        val peers =
            (1..6).map { index ->
                PeerRates(
                    peer(index),
                    interested = true,
                    downloadRate = index * 1000L,
                    uploadRate = (7 - index) * 1000L,
                )
            }

        listOf(false, true).forEach { seeding ->
            val decision =
                Choker(regularSlots = 3, random = Random(index(seeding)))
                    .pass(peers, seeding = seeding, rotateOptimistic = true)
            val rate = { rates: PeerRates -> if (seeding) rates.uploadRate else rates.downloadRate }
            val regular = decision.unchoked - setOfNotNull(decision.optimistic)
            val chokedButInterested =
                peers.filter { it.peer !in decision.unchoked && it.peer != decision.optimistic }

            regular.forEach { unchoked ->
                val theirRate = rate(peers.first { it.peer == unchoked })
                chokedButInterested.forEach { choked ->
                    assertTrue(
                        theirRate >= rate(choked),
                        "seeding=$seeding: $unchoked was served at $theirRate " +
                            "while ${choked.peer} at ${rate(choked)} was not",
                    )
                }
            }
            assertTrue(regular.isNotEmpty(), "seeding=$seeding: nobody was served, so nothing is proven")
        }
    }

    private fun index(seeding: Boolean): Int = if (seeding) 5 else 9

    @Test
    fun aPeerThatWantsNothingIsNotServedAheadOfOneThatDoes() {
        val peers =
            listOf(
                PeerRates(peer(1), interested = false, downloadRate = 9_000, uploadRate = 0),
                PeerRates(peer(2), interested = true, downloadRate = 1, uploadRate = 0),
            )
        val decision = Choker(regularSlots = 1, random = Random(2)).pass(peers, false, rotateOptimistic = true)
        assertTrue(peer(2) in decision.unchoked || decision.optimistic == peer(2))
    }

    @Test
    fun noPeersIsNoDecisionRatherThanACrash() {
        val decision = Choker().pass(emptyList(), seeding = false, rotateOptimistic = true)
        assertTrue(decision.unchoked.isEmpty())
        assertEquals(null, decision.optimistic)
    }

    @Test
    fun anOptimisticPeerThatLeftIsReplaced() {
        val choker = Choker(random = Random(7))
        val peers = eightPeers()
        val first = assertNotNull(choker.pass(peers, false, rotateOptimistic = true).optimistic)
        val without = peers.filter { it.peer != first }
        val second = choker.pass(without, false, rotateOptimistic = false).optimistic
        assertTrue(second != first && second != null, "a peer that is gone cannot keep the slot")
    }
}
