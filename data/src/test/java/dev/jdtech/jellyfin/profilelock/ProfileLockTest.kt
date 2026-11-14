package dev.jdtech.jellyfin.profilelock

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.User
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLockTest {
    private val alice = User(id = UUID.randomUUID(), name = "Alice", serverId = "s")
    private val bob = User(id = UUID.randomUUID(), name = "Bob", serverId = "s")
    private val users = mutableMapOf(alice.id to alice, bob.id to bob)
    private val lock = ProfileLock(fakeDao(users))

    @Test
    fun `set, verify and clear a PIN`() = runBlocking {
        assertFalse(lock.hasPin(alice.id))

        lock.setPin(alice.id, "1234")

        assertTrue(lock.hasPin(alice.id))
        assertNotNull(users.getValue(alice.id).pinSalt)
        assertFalse(users.getValue(alice.id).pinHash!!.contains("1234"))
        assertTrue(lock.verifyPin(alice.id, "1234"))
        assertFalse(lock.verifyPin(alice.id, "4321"))

        lock.clearPin(alice.id)

        assertFalse(lock.hasPin(alice.id))
        assertNull(users.getValue(alice.id).pinHash)
        assertNull(users.getValue(alice.id).pinSalt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a PIN of three digits is refused`() = runBlocking { lock.setPin(alice.id, "123") }

    @Test
    fun `setting a PIN unlocks that profile`() = runBlocking {
        lock.setPin(alice.id, "1234")

        assertEquals(setOf(alice.id), lock.unlockedUserIds.value)
        assertFalse(lock.needsPinToSwitch(alice.id))
    }

    @Test
    fun `switching to another profile locks the first one again`() = runBlocking {
        lock.setPin(alice.id, "1234")
        lock.unlock(bob.id)

        assertTrue(lock.needsPinToSwitch(alice.id))
        assertFalse(lock.needsPinToSwitch(bob.id))
        // Bob is current now, and his profile is open.
        assertFalse(ProfileLock.isLockedOnStart(users[bob.id], lock.unlockedUserIds.value))
    }

    @Test
    fun `a new process starts locked`() = runBlocking {
        lock.setPin(alice.id, "1234")
        val restarted = ProfileLock(fakeDao(users))

        assertTrue(restarted.needsPinToSwitch(alice.id))
        assertTrue(ProfileLock.isLockedOnStart(users[alice.id], restarted.unlockedUserIds.value))
    }

    @Test
    fun `the start lock follows the profile's setting`() = runBlocking {
        lock.setPin(alice.id, "1234")
        lock.setPinOnStart(alice.id, false)

        assertFalse(ProfileLock.isLockedOnStart(users[alice.id], emptySet()))
        assertFalse(ProfileLock.isLockedOnStart(users[bob.id], emptySet()))
        assertFalse(ProfileLock.isLockedOnStart(null, emptySet()))
    }

    @Test
    fun `a profile without a PIN verifies anything`() = runBlocking {
        assertTrue(lock.verifyPin(bob.id, "0000"))
        assertFalse(lock.verifyPin(UUID.randomUUID(), "0000"))
    }

    @Test
    fun `set, verify and clear a pattern`() = runBlocking {
        val pattern = listOf(0, 1, 2, 5, 8)
        lock.setPattern(alice.id, pattern)

        assertTrue(lock.hasPin(alice.id))
        assertEquals(ProfileLockType.PATTERN, lock.lockType(alice.id))
        assertEquals("pattern", users.getValue(alice.id).lockType)
        assertFalse(users.getValue(alice.id).pinHash!!.contains("0-1-2-5-8"))
        assertTrue(lock.verifyPattern(alice.id, pattern))
        assertFalse(lock.verifyPattern(alice.id, listOf(8, 5, 2, 1, 0)))
        assertFalse(lock.verifyPattern(alice.id, listOf(0, 1, 2, 5)))

        lock.clearPin(alice.id)

        assertNull(lock.lockType(alice.id))
        assertEquals("pin", users.getValue(alice.id).lockType)
        assertNull(users.getValue(alice.id).pinHash)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pattern of three dots is refused`() = runBlocking {
        lock.setPattern(alice.id, listOf(0, 1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pattern that uses a dot twice is refused`() = runBlocking {
        lock.setPattern(alice.id, listOf(0, 1, 2, 1))
    }

    @Test
    fun `a profile has one lock type at a time`() = runBlocking {
        lock.setPin(alice.id, "1234")
        lock.setPattern(alice.id, listOf(6, 3, 0, 1, 2))

        assertEquals(ProfileLockType.PATTERN, lock.lockType(alice.id))
        assertFalse(lock.verifyPin(alice.id, "1234"))
        assertTrue(lock.verifyPattern(alice.id, listOf(6, 3, 0, 1, 2)))

        lock.setPin(alice.id, "1234")

        assertEquals(ProfileLockType.PIN, lock.lockType(alice.id))
        assertTrue(lock.verifyPin(alice.id, "1234"))
        assertFalse(lock.verifyPattern(alice.id, listOf(6, 3, 0, 1, 2)))
    }

    @Test
    fun `digits never unlock a pattern, whatever they spell`() = runBlocking {
        lock.setPattern(alice.id, listOf(1, 2, 3, 4))

        assertFalse(lock.verifyPin(alice.id, "1234"))
        assertFalse(lock.verify(alice.id, ProfileLockEntry.Pin("1-2-3-4")))
        assertTrue(lock.verify(alice.id, ProfileLockEntry.Pattern(listOf(1, 2, 3, 4))))
    }

    @Test
    fun `setting a pattern unlocks that profile and locks it in a new process`() = runBlocking {
        lock.setPattern(alice.id, listOf(0, 3, 6, 7))
        assertFalse(lock.needsPinToSwitch(alice.id))

        val restarted = ProfileLock(fakeDao(users))
        assertTrue(restarted.needsPinToSwitch(alice.id))
        assertTrue(ProfileLock.isLockedOnStart(users[alice.id], restarted.unlockedUserIds.value))
    }

    companion object {
        /** Just the user calls [ProfileLock] makes, on an in-memory table. */
        fun fakeDao(users: MutableMap<UUID, User>): ServerDatabaseDao =
            Proxy.newProxyInstance(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getUser" -> users[args[0] as UUID]
                    "updateUserPin" -> {
                        val id = args[0] as UUID
                        users[id]?.let {
                            users[id] =
                                it.copy(
                                    pinHash = args[1] as String?,
                                    pinSalt = args[2] as String?,
                                    lockType = "pin",
                                )
                        }
                        Unit
                    }
                    "updateUserPattern" -> {
                        val id = args[0] as UUID
                        users[id]?.let {
                            users[id] =
                                it.copy(
                                    pinHash = args[1] as String,
                                    pinSalt = args[2] as String,
                                    lockType = "pattern",
                                )
                        }
                        Unit
                    }
                    "updateUserPinOnStart" -> {
                        val id = args[0] as UUID
                        users[id]?.let { users[id] = it.copy(pinOnStart = args[1] as Boolean) }
                        Unit
                    }
                    "toString" -> "FakeServerDatabaseDao"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ServerDatabaseDao
    }
}
