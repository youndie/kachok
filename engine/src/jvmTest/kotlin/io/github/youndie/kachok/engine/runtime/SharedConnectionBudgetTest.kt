package io.github.youndie.kachok.engine.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B-152: one count of connections and of dials in flight for the whole client. */
class SharedConnectionBudgetTest {
    @Test
    fun aDialHoldsAConnectionSlotAndKeepsItOnlyIfItConnects() {
        val budget = SharedConnectionBudget(maxConnections = 2, maxDials = 5)
        assertTrue(budget.tryDial())
        assertTrue(budget.tryDial())
        assertFalse(budget.tryDial(), "two dials in flight against a cap of two connections, and a third went out")
        budget.dialEnded(connected = false)
        assertEquals(1, budget.connections, "a failed dial kept its connection slot")
        budget.dialEnded(connected = true)
        assertEquals(1, budget.connections)
        assertEquals(0, budget.dialling)
        assertTrue(budget.tryAccept())
        assertFalse(budget.tryAccept(), "an incoming peer was taken past the cap")
        budget.closed()
        assertTrue(budget.tryAccept())
    }

    @Test
    fun dialsInFlightHaveTheirOwnCap() {
        val budget = SharedConnectionBudget(maxConnections = 0, maxDials = 2)
        assertTrue(budget.tryDial())
        assertTrue(budget.tryDial())
        assertFalse(budget.tryDial(), "a third dial went out against a cap of two")
        budget.dialEnded(connected = true)
        assertTrue(budget.tryDial(), "a dial that ended did not give its slot back")
        assertTrue(budget.tryAccept(), "no connection cap, and an incoming peer was refused")
    }

    @Test
    fun zeroIsNoCapAndARetuneTakesEffectOnTheNextAsk() {
        val budget = SharedConnectionBudget(maxConnections = 0, maxDials = 0)
        repeat(1000) { assertTrue(budget.tryDial()) }
        budget.retune(maxConnections = 1000, maxDials = 1000)
        assertFalse(budget.tryDial(), "a cap lowered below what is held let another dial out")
    }
}
