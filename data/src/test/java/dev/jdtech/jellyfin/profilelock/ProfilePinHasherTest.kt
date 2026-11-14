package dev.jdtech.jellyfin.profilelock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinHasherTest {
    @Test
    fun `a PIN is 4 to 6 digits`() {
        assertTrue(ProfilePinHasher.isValid("1234"))
        assertTrue(ProfilePinHasher.isValid("123456"))
        assertFalse(ProfilePinHasher.isValid("123"))
        assertFalse(ProfilePinHasher.isValid("1234567"))
        assertFalse(ProfilePinHasher.isValid("12a4"))
        assertFalse(ProfilePinHasher.isValid("12 34"))
        // Other scripts' digits are not on the keypad.
        assertFalse(ProfilePinHasher.isValid("١٢٣٤"))
    }

    @Test
    fun `the right PIN verifies and a wrong one does not`() {
        val salt = ProfilePinHasher.newSalt()
        val hash = ProfilePinHasher.hash("4821", salt)

        assertTrue(ProfilePinHasher.verify("4821", salt, hash))
        assertFalse(ProfilePinHasher.verify("4822", salt, hash))
        assertFalse(ProfilePinHasher.verify("48210", salt, hash))
        assertFalse(ProfilePinHasher.verify("", salt, hash))
    }

    @Test
    fun `the hash is stable for a salt and differs between salts`() {
        val salt = ProfilePinHasher.newSalt()
        assertEquals(ProfilePinHasher.hash("0000", salt), ProfilePinHasher.hash("0000", salt))

        val other = ProfilePinHasher.newSalt()
        assertNotEquals(salt, other)
        assertNotEquals(ProfilePinHasher.hash("0000", salt), ProfilePinHasher.hash("0000", other))
    }

    @Test
    fun `the hash does not contain the PIN`() {
        val salt = ProfilePinHasher.newSalt()
        assertFalse(ProfilePinHasher.hash("123456", salt).contains("123456"))
    }

    @Test
    fun `a damaged hash never verifies`() {
        val salt = ProfilePinHasher.newSalt()
        assertFalse(ProfilePinHasher.verify("1234", salt, "not base64!"))
        assertFalse(
            ProfilePinHasher.verify("1234", "not base64!", ProfilePinHasher.hash("1234", salt))
        )
    }
}
