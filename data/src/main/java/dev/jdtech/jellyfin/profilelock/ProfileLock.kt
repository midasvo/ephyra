package dev.jdtech.jellyfin.profilelock

import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.ProfileLockType
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.models.hasPin
import dev.jdtech.jellyfin.models.profileLockType
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * CE (#299): the local PIN of a profile, and which profile this process has unlocked.
 *
 * The PIN is a salted hash on the user's row ([User.pinHash]); it never leaves the device. A
 * profile stays unlocked until the app switches to another profile or the process ends. Removing
 * the user or its server, or signing in again (which rewrites the row), removes the PIN.
 *
 * Since #330 the lock can also be an unlock pattern ([User.lockType]), offered on phone only. A
 * profile has one lock at a time: setting a pattern replaces its PIN and the other way round.
 *
 * One instance per process: `core` provides it as a singleton.
 */
class ProfileLock(
    private val database: ServerDatabaseDao,
    /** Where the deliberately slow hashing runs. */
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _unlockedUserIds = MutableStateFlow<Set<UUID>>(emptySet())

    /**
     * The profiles unlocked, or switched to without a PIN, in this process. Only the last one
     * counts for a switch ([needsPinToSwitch]); the earlier ones stay in the set so the start lock
     * does not flash on the old profile while the database catches up with a switch.
     */
    val unlockedUserIds: StateFlow<Set<UUID>> = _unlockedUserIds.asStateFlow()

    @Volatile private var lastUnlockedUserId: UUID? = null

    suspend fun hasPin(userId: UUID): Boolean = database.getUser(userId)?.hasPin == true

    /**
     * Sets or replaces the PIN. The profile counts as unlocked (before the row changes, so the
     * start lock never shows): its owner just typed the PIN.
     */
    suspend fun setPin(userId: UUID, pin: String) {
        require(ProfilePinHasher.isValid(pin)) { "A PIN is 4 to 6 digits" }
        val salt = ProfilePinHasher.newSalt()
        val hash = withContext(hashDispatcher) { ProfilePinHasher.hash(pin, salt) }
        unlock(userId)
        database.updateUserPin(userId, hash, salt)
    }

    /**
     * CE (#330): sets or replaces the lock with a pattern, replacing a PIN. Unlocks the profile, as
     * [setPin] does.
     */
    suspend fun setPattern(userId: UUID, dots: List<Int>) {
        require(ProfilePattern.isValid(dots)) { "A pattern connects 4 or more distinct dots" }
        val salt = ProfilePinHasher.newSalt()
        val hash =
            withContext(hashDispatcher) {
                ProfilePinHasher.hash(ProfilePattern.serialize(dots), salt)
            }
        unlock(userId)
        database.updateUserPattern(userId, hash, salt)
    }

    /** Removes the profile's lock, PIN or pattern. */
    suspend fun clearPin(userId: UUID) {
        database.updateUserPin(userId, null, null)
    }

    /** CE (#330): the type of the profile's lock, or null when it has none. */
    suspend fun lockType(userId: UUID): ProfileLockType? = database.getUser(userId)?.profileLockType

    /** Changed from inside the profile, so it stays open when the start lock is turned on. */
    suspend fun setPinOnStart(userId: UUID, pinOnStart: Boolean) {
        unlock(userId)
        database.updateUserPinOnStart(userId, pinOnStart)
    }

    /**
     * True when [pin] is the profile's PIN, or the profile has no lock. False for a pattern lock.
     */
    suspend fun verifyPin(userId: UUID, pin: String): Boolean =
        verify(userId, ProfileLockEntry.Pin(pin))

    /** CE (#330): true when [dots] is the profile's pattern, or the profile has no lock. */
    suspend fun verifyPattern(userId: UUID, dots: List<Int>): Boolean =
        verify(userId, ProfileLockEntry.Pattern(dots))

    /**
     * CE (#330): true when [entry] unlocks the profile, or the profile has no lock. An entry of the
     * other type (digits for a pattern lock) never does.
     */
    suspend fun verify(userId: UUID, entry: ProfileLockEntry): Boolean {
        val user = database.getUser(userId) ?: return false
        val hash = user.pinHash ?: return true
        val salt = user.pinSalt ?: return false
        if (user.profileLockType != entry.type) return false
        return withContext(hashDispatcher) { ProfilePinHasher.verify(entry, salt, hash) }
    }

    /** Whether switching to [userId] has to ask for its PIN first. */
    suspend fun needsPinToSwitch(userId: UUID): Boolean =
        userId != lastUnlockedUserId && hasPin(userId)

    /** Marks [userId] as the unlocked profile; switching to any other profile asks again. */
    fun unlock(userId: UUID) {
        lastUnlockedUserId = userId
        _unlockedUserIds.value += userId
    }

    companion object {
        /** Whether [user], the profile the app is in, has to be unlocked before Home shows. */
        fun isLockedOnStart(user: User?, unlockedUserIds: Set<UUID>): Boolean =
            user != null && user.hasPin && user.pinOnStart && user.id !in unlockedUserIds
    }
}
