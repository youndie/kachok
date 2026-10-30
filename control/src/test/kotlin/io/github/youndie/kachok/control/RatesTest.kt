package io.github.youndie.kachok.control

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** B-151: a rate as a person writes it — in bits or in bytes — and as the client says it back. */
class RatesTest {
    @Test
    fun bitsAndBytesAreToldApartByTheCaseOfB() {
        assertEquals(1_250_000, Rates.parse("10 Mbit/s"))
        assertEquals(1_250_000, Rates.parse("10Mb/s"))
        assertEquals(10_000_000, Rates.parse("10 MB/s"))
        assertEquals(1_250_000, Rates.parse("10 мбит/с"))
        assertEquals(1_536, Rates.parse("1.5 KiB/s"))
        assertEquals(2_097_152, Rates.parse("2 MiB/s"))
        assertEquals(500_000, Rates.parse("0,5 MB"))
    }

    @Test
    fun noLimitHasSeveralSpellingsAndABareNumberIsRefused() {
        listOf("off", "0", "no limit", "unlimited", "", "нет").forEach { assertEquals(0, Rates.parse(it), it) }
        assertContains(assertFailsWith<IllegalArgumentException> { Rates.parse("1200") }.message.orEmpty(), "no unit")
        assertContains(
            assertFailsWith<IllegalArgumentException> { Rates.parse("fast") }.message.orEmpty(),
            "not a rate",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { Rates.parse("5 Tbit/s") }.message.orEmpty(),
            "not one",
        )
    }

    @Test
    fun aRateIsSaidBackInBothUnits() {
        assertEquals("10 Mbit/s (1.19 MiB/s)", Rates.describe(1_250_000))
        assertEquals("800 kbit/s (97.7 KiB/s)", Rates.describe(100_000))
        assertEquals("no limit", Rates.describe(0))
    }
}
