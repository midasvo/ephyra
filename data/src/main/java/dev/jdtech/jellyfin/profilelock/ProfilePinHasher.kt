package dev.jdtech.jellyfin.profilelock

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * CE (#299): hashes a profile PIN (since #330 also an unlock pattern, as [ProfilePattern.serialize]
 * text) with PBKDF2-HMAC-SHA256 and a random per-user salt. The PIN only keeps someone on the couch
 * out of a profile: anyone with the app's data can try all 10^6 PINs, so the work factor is kept
 * low enough for a slow TV box to check a PIN without a visible wait.
 */
object ProfilePinHasher {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    private val random = SecureRandom()

    /** 4 to 6 ASCII digits. */
    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun hash(pin: String, salt: String): String {
        val spec =
            PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, KEY_BITS)
        try {
            val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(key)
        } finally {
            spec.clearPassword()
        }
    }

    /** Compares in constant time, so the check does not leak how much of a hash matched. */
    fun verify(pin: String, salt: String, expectedHash: String): Boolean =
        verify(ProfileLockEntry.Pin(pin), salt, expectedHash)

    /** CE (#330): [entry] of either type; false for one that is not [ProfileLockEntry.isValid]. */
    fun verify(entry: ProfileLockEntry, salt: String, expectedHash: String): Boolean {
        if (!entry.isValid) return false
        val expected = runCatching { Base64.getDecoder().decode(expectedHash) }.getOrNull()
        val actual =
            runCatching { Base64.getDecoder().decode(hash(entry.secret, salt)) }.getOrNull()
        return expected != null && actual != null && MessageDigest.isEqual(actual, expected)
    }
}
