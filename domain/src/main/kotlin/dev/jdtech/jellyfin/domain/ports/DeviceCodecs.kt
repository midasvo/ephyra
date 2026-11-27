package dev.jdtech.jellyfin.domain.ports

import dev.jdtech.jellyfin.player.DeviceDecoders
import dev.jdtech.jellyfin.player.ProbedCodecs

/** What this device can decode. Probed once; the display check is made on every call. */
interface DeviceCodecs {
    /** The decoders, probed on first use and kept. */
    fun probedCodecsSnapshot(): ProbedCodecs

    /**
     * Whether the display takes Dolby Vision. Read again on every call: a TV box can be plugged
     * into another TV at any time.
     */
    fun displaySupportsDolbyVision(): Boolean

    companion object {
        val NONE: DeviceCodecs =
            object : DeviceCodecs {
                override fun probedCodecsSnapshot(): ProbedCodecs = ProbedCodecs.EMPTY

                override fun displaySupportsDolbyVision(): Boolean = false
            }
    }
}

/**
 * Snapshot of what the device decodes now. Skips [DeviceCodecs.displaySupportsDolbyVision] when
 * [checkDisplayDolbyVision] is false.
 */
fun DeviceCodecs.snapshot(checkDisplayDolbyVision: Boolean = true): DeviceDecoders =
    DeviceDecoders(
        probed = probedCodecsSnapshot(),
        displaySupportsDolbyVision = checkDisplayDolbyVision && displaySupportsDolbyVision(),
    )

/** The device report the About screen copies. Implemented by the server adapter. */
fun interface DeviceReportSource {
    suspend fun buildJson(
        appVersion: String,
        buildType: String,
        transcodeDolbyVision: Boolean,
        directPlayDolbyVision: Boolean,
        decoders: DeviceDecoders,
    ): String
}
