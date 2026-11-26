package dev.jdtech.jellyfin.player

/** The largest picture a Dolby Vision level allows. */
data class DolbyVisionLimit(val maxWidth: Int, val maxHeight: Int, val maxFramerate: Int)

/**
 * Result of the device's decoder probe, reduced to plain types so the profile assembly stays pure
 * and testable.
 */
data class ProbedCodecs(
    /** ffmpeg video codec name -> set of decoder profile names the device advertises. */
    val videoCodecProfiles: Map<String, Set<String>>,
    /** ffmpeg audio codec names the device can decode. */
    val audioCodecs: Set<String>,
    /** Dolby Vision profile number -> the highest level a decoder advertises for it. */
    val dolbyVisionProfiles: Map<Int, DolbyVisionLimit> = emptyMap(),
) {
    companion object {
        val EMPTY = ProbedCodecs(emptyMap(), emptySet())
    }
}

/**
 * What this device can decode at the moment a profile is built: the [probed] decoders and whether
 * the display currently accepts Dolby Vision ([displaySupportsDolbyVision]).
 */
data class DeviceDecoders(
    val probed: ProbedCodecs = ProbedCodecs.EMPTY,
    val displaySupportsDolbyVision: Boolean = false,
)
