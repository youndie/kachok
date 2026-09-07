package io.github.youndie.kachok.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdsTest {
    @Test
    fun infoHashIsExactlyTwentyBytes() {
        assertEquals(20, InfoHash(ByteArray(20)).bytes.size)
        assertFailsWith<IllegalArgumentException> { InfoHash(ByteArray(19)) }
    }

    @Test
    fun peerIdIsExactlyTwentyBytes() {
        assertFailsWith<IllegalArgumentException> { PeerId(ByteArray(21)) }
    }
}
