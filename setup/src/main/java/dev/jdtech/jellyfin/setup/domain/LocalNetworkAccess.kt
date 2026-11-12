package dev.jdtech.jellyfin.setup.domain

import android.content.Context
import android.os.Build
import dev.jdtech.jellyfin.settings.domain.models.PermissionDefinitions
import dev.jdtech.jellyfin.utils.isPermissionGranted
import java.net.InetAddress
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CE (#376): the local network permission of Android 17. While it is denied, discovery and every
 * address on the local network fail, and the SDK reports neither. The setup screens ask here, so
 * they can say that the permission is the reason.
 */
interface LocalNetworkAccess {
    /** Whether local network access is denied. Always false before Android 17. */
    fun isDenied(): Boolean

    /**
     * The name the system settings list the permission under ("Nearby devices"), in the system's
     * language, or null when the system does not tell.
     */
    suspend fun systemSettingName(): String?
}

/**
 * The one place that decides whether the permission applies: Android 16 and older do not have it,
 * and report an unknown permission as not granted.
 */
fun localNetworkAccessDenied(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.CINNAMON_BUN && !granted

/**
 * Whether [address] (the 4 or 16 bytes of an IP address) is one that Android's local network
 * protection blocks: private, link-local, multicast and broadcast addresses. Loopback is the device
 * itself and is not blocked.
 */
fun isLocalNetworkAddress(address: ByteArray): Boolean {
    val b = address.map { it.toInt() and 0xFF }
    return when (b.size) {
        4 ->
            b[0] == 10 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) ||
                // Carrier-grade NAT, 100.64.0.0/10: Android counts it as local, and it is where
                // Tailscale puts its addresses.
                (b[0] == 100 && b[1] in 64..127) ||
                b[0] in 224..239 ||
                b.all { it == 255 }
        16 ->
            if (b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF) {
                // An IPv4 address written as IPv6 (::ffff:a.b.c.d).
                isLocalNetworkAddress(address.copyOfRange(12, 16))
            } else {
                (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) || // link-local, fe80::/10
                    (b[0] and 0xFE) == 0xFC || // unique local, fc00::/7
                    b[0] == 0xFF // multicast, ff00::/8
            }
        else -> false
    }
}

/** The host of a server address as it is typed ("10.0.2.2:8096", "https://example.org/jf"). */
fun hostOfServerAddress(address: String): String? {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    return try {
        URI(withScheme).host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

/** The bytes of [host] when it is an IP address, without a name lookup; null for a host name. */
fun parseIpAddress(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size == 4 && parts.all { part -> part.length in 1..3 && part.all(Char::isDigit) }) {
        val numbers = parts.map(String::toInt)
        return if (numbers.all { it <= 255 }) numbers.map(Int::toByte).toByteArray() else null
    }
    if (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) {
        // With a colon in it this can only be an IPv6 address: no lookup happens.
        return try {
            InetAddress.getByName(host).address
        } catch (_: Exception) {
            null
        }
    }
    return null
}

/**
 * Whether [host] is on the local network, given the addresses its name [resolved] to. A name that
 * did not resolve counts as local when it can only be a local one: a single label ("nas") or an
 * mDNS name ("nas.local").
 */
fun isLocalNetworkHost(host: String, resolved: List<ByteArray>): Boolean {
    if (resolved.isNotEmpty()) return resolved.any(::isLocalNetworkAddress)
    val name = host.trim().trimEnd('.').lowercase()
    if (name.isEmpty() || name == "localhost") return false
    return '.' !in name || name.endsWith(".local")
}

/** The addresses of [host]: its own bytes for an IP address, a name lookup otherwise. */
suspend fun resolveHost(host: String): List<ByteArray> {
    parseIpAddress(host)?.let {
        return listOf(it)
    }
    return withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).map { it.address }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Whether a failed connection to [address] is explained by the denied permission: access is denied
 * and the address is on the local network. An internet address, or an address that cannot be read,
 * keeps the usual error.
 */
suspend fun LocalNetworkAccess.blocks(
    address: String,
    resolve: suspend (String) -> List<ByteArray> = ::resolveHost,
): Boolean {
    if (!isDenied()) return false
    val host = hostOfServerAddress(address) ?: return false
    return isLocalNetworkHost(host, resolve(host))
}

/**
 * Whether a refused permission request should open the system settings: only when the system showed
 * no dialog, which it does once the permission was denied twice. Then it reports "no rationale"
 * both before and after the request. After a first denial it reports a rationale; after the second
 * it did so before the request. In both the user just answered a dialog, and is left alone.
 */
fun shouldOpenSettingsAfterRefusal(rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean =
    !rationaleBefore && !rationaleAfter

class LocalNetworkAccessImpl(private val context: Context) : LocalNetworkAccess {
    private val permission = PermissionDefinitions.localNetwork

    override fun isDenied(): Boolean =
        localNetworkAccessDenied(Build.VERSION.SDK_INT, isPermissionGranted(context, permission))

    override suspend fun systemSettingName(): String? = systemPermissionGroupLabel(context, permission.id)
}

/**
 * The label of the group the system settings list [permissionId] under. The platform keeps that
 * mapping in the permission controller and answers through a callback, so this never blocks.
 */
suspend fun systemPermissionGroupLabel(context: Context, permissionId: String): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val packageManager = context.packageManager
    return try {
        val group =
            withTimeoutOrNull(2_000) {
                suspendCancellableCoroutine<String?> { continuation ->
                    packageManager.getGroupOfPlatformPermission(
                        permissionId,
                        context.mainExecutor,
                    ) { group ->
                        if (continuation.isActive) continuation.resume(group)
                    }
                }
            } ?: return null
        packageManager.getPermissionGroupInfo(group, 0).loadLabel(packageManager).toString().takeIf {
            it.isNotBlank()
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
}
