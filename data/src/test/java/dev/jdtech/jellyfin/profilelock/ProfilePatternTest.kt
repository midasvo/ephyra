package dev.jdtech.jellyfin.profilelock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePatternTest {
    @Test
    fun `a pattern connects 4 to 9 distinct dots on the grid`() {
        assertTrue(ProfilePattern.isValid(listOf(0, 1, 2, 5)))
        assertTrue(ProfilePattern.isValid((0..8).toList()))
        assertFalse(ProfilePattern.isValid(listOf(0, 1, 2)))
        assertFalse(ProfilePattern.isValid(listOf(0, 1, 2, 2)))
        assertFalse(ProfilePattern.isValid(listOf(0, 1, 2, 9)))
        assertFalse(ProfilePattern.isValid(listOf(-1, 1, 2, 3)))
    }

    @Test
    fun `a pattern is stored as its dots joined by dashes`() {
        assertEquals("0-1-2-5-8", ProfilePattern.serialize(listOf(0, 1, 2, 5, 8)))
    }

    @Test
    fun `a line across an unconnected dot connects it first`() {
        assertEquals(listOf(0, 1, 2), ProfilePattern.connect(listOf(0), 2))
        assertEquals(listOf(0, 4, 8), ProfilePattern.connect(listOf(0), 8))
        assertEquals(listOf(2, 4, 6), ProfilePattern.connect(listOf(2), 6))
        assertEquals(listOf(1, 4, 7), ProfilePattern.connect(listOf(1), 7))
    }

    @Test
    fun `a line across a connected dot only adds the end`() {
        assertEquals(listOf(1, 0, 2), ProfilePattern.connect(listOf(1, 0), 2))
    }

    @Test
    fun `neighbours and knight moves connect directly`() {
        assertEquals(listOf(0, 1), ProfilePattern.connect(listOf(0), 1))
        assertEquals(listOf(0, 5), ProfilePattern.connect(listOf(0), 5))
        assertEquals(listOf(0, 7), ProfilePattern.connect(listOf(0), 7))
    }

    @Test
    fun `no dot is connected twice`() {
        assertEquals(listOf(0, 1), ProfilePattern.connect(listOf(0, 1), 0))
        assertEquals(listOf(0, 1), ProfilePattern.connect(listOf(0, 1), 9))
        assertEquals(listOf(4), ProfilePattern.connect(emptyList(), 4))
    }

    @Test
    fun `a pattern hashes and verifies like a PIN`() {
        val salt = ProfilePinHasher.newSalt()
        val hash = ProfilePinHasher.hash("0-1-2-5-8", salt)

        assertTrue(
            ProfilePinHasher.verify(ProfileLockEntry.Pattern(listOf(0, 1, 2, 5, 8)), salt, hash)
        )
        assertFalse(
            ProfilePinHasher.verify(ProfileLockEntry.Pattern(listOf(0, 1, 2, 5)), salt, hash)
        )
        assertFalse(ProfilePinHasher.verify(ProfileLockEntry.Pattern(listOf(0, 1, 2)), salt, hash))
    }
}
