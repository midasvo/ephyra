package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.player.DeviceDecoders
import dev.jdtech.jellyfin.player.DolbyVisionLimit
import dev.jdtech.jellyfin.player.ProbedCodecs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.jupiter.api.Test

/**
 * Tests the pure profile-assembly half of [JellyfinDeviceProfiles]. The `MediaCodecList` hardware
 * probe needs a device and is not covered here.
 */
class JellyfinDeviceProfilesTest {

    private val uhd60 = DolbyVisionLimit(3840, 2160, 60)

    private fun probedWithDolbyVision(vararg profiles: Pair<Int, DolbyVisionLimit>) =
        ProbedCodecs(
            videoCodecProfiles = mapOf("hevc" to setOf("Main 10"), "av1" to emptySet()),
            audioCodecs = setOf("aac"),
            dolbyVisionProfiles = mapOf(*profiles),
        )

    private fun rangeTypes(profile: DeviceProfile, codec: String, container: String = "mkv") =
        profile.codecProfiles
            .filter { it.codec == codec && it.container == container }
            .flatMap { it.conditions }
            .first { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
            .value
            .orEmpty()
            .split("|")

    private fun dolbyVisionLimits(
        profile: DeviceProfile,
        codec: String,
        container: String = "mkv",
    ) =
        profile.codecProfiles.filter {
            it.codec == codec && it.container == container && it.applyConditions.isNotEmpty()
        }

    private fun hevcRangeCondition(probed: ProbedCodecs) =
        JellyfinDeviceProfiles.buildDeviceProfile(probed)
            .codecProfiles
            .filter { it.codec == "hevc" }
            .flatMap { it.conditions }
            .firstOrNull { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }

    @Test
    fun `dolby vision stays out of direct play without a dolby vision decoder`() {
        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision())

        for (codec in listOf("hevc", "av1")) {
            val rangeTypes = rangeTypes(profile, codec)
            assertTrue("HDR10" in rangeTypes, message = "$codec must allow HDR10")
            assertFalse(
                rangeTypes.any { it.startsWith("DOVI") },
                message = "DV ranges leaked into $codec direct play: $rangeTypes",
            )
            assertEquals(emptyList<Any>(), dolbyVisionLimits(profile, codec))
        }
    }

    @Test
    fun `an advertised dolby vision profile only opens its own range types`() {
        val cases =
            mapOf(
                5 to listOf("DOVI"),
                7 to listOf("DOVIWithEL", "DOVIWithELHDR10Plus"),
                8 to listOf("DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus"),
            )

        for ((dvProfile, expected) in cases) {
            val profile =
                JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision(dvProfile to uhd60))

            assertEquals(
                expected,
                rangeTypes(profile, "hevc").filter { it.startsWith("DOVI") },
                message = "hevc range types for DV profile $dvProfile",
            )
            assertFalse(
                rangeTypes(profile, "av1").any { it.startsWith("DOVI") },
                message = "an HEVC DV decoder says nothing about AV1",
            )
        }
    }

    @Test
    fun `dolby vision profile 10 opens av1 and leaves hevc closed`() {
        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision(10 to uhd60))

        assertEquals(
            listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus"),
            rangeTypes(profile, "av1").filter { it.startsWith("DOVI") },
        )
        assertFalse(rangeTypes(profile, "hevc").any { it.startsWith("DOVI") })
    }

    @Test
    fun `av1 is range restricted even though no av1 profiles are probed`() {
        // AV1 used to get no codec profile at all, so profile 10.0 was direct-played
        // on devices that can only show it with wrong colors.
        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision())

        assertFalse("DOVI" in rangeTypes(profile, "av1"))
        assertFalse("DOVI" in rangeTypes(profile, "av1", container = "mp4"))
    }

    @Test
    fun `out-of-spec dolby vision is never direct-played`() {
        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(
                probedWithDolbyVision(5 to uhd60, 7 to uhd60, 8 to uhd60, 10 to uhd60)
            )

        assertFalse("DOVIInvalid" in rangeTypes(profile, "hevc"))
        assertFalse("DOVIInvalid" in rangeTypes(profile, "av1"))
    }

    @Test
    fun `dolby vision needs a container media3 reads the configuration from`() {
        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision(5 to uhd60))

        assertTrue("DOVI" in rangeTypes(profile, "hevc", container = "mkv"))
        assertTrue("DOVI" in rangeTypes(profile, "hevc", container = "mp4"))
        assertFalse("DOVI" in rangeTypes(profile, "hevc", container = "mpegts"))
        assertFalse("DOVI" in rangeTypes(profile, "hevc", container = "3gp"))
    }

    @Test
    fun `the switch keeps all dolby vision out of direct play whatever is advertised`() {
        val probed = probedWithDolbyVision(5 to uhd60, 8 to uhd60, 10 to uhd60)

        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(probed, directPlayDolbyVision = false)

        assertEquals(
            JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision()).codecProfiles,
            profile.codecProfiles,
        )
    }

    @Test
    fun `dolby vision is held to the advertised level`() {
        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(
                probedWithDolbyVision(
                    5 to DolbyVisionLimit(1920, 1080, 60),
                    8 to DolbyVisionLimit(3840, 2160, 24),
                )
            )

        val limits =
            dolbyVisionLimits(profile, "hevc").associate { limit ->
                limit.applyConditions.single().value to
                    limit.conditions.associate { it.property to it.value }
            }

        assertEquals<Map<*, *>>(
            mapOf(
                "DOVI" to
                    mapOf(
                        ProfileConditionValue.WIDTH to "1920",
                        ProfileConditionValue.HEIGHT to "1080",
                        ProfileConditionValue.VIDEO_FRAMERATE to "60",
                    ),
                "DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|DOVIWithHDR10Plus" to
                    mapOf(
                        ProfileConditionValue.WIDTH to "3840",
                        ProfileConditionValue.HEIGHT to "2160",
                        ProfileConditionValue.VIDEO_FRAMERATE to "24",
                    ),
            ),
            limits,
        )
    }

    @Test
    fun `the level limit leaves streams with an unknown range alone`() {
        // The server treats a condition on an unknown value as met unless it is
        // required, which would hold those streams to the DV limit as well.
        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision(5 to uhd60))

        val applyCondition = dolbyVisionLimits(profile, "hevc").single().applyConditions.single()

        assertEquals(ProfileConditionValue.VIDEO_RANGE_TYPE, applyCondition.property)
        assertTrue(applyCondition.isRequired)
    }

    @Test
    fun `hevc codec profile excludes every Dolby Vision range from direct play`() {
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("hevc" to setOf("Main", "Main 10")),
                audioCodecs = setOf("aac"),
            )

        val rangeCondition = hevcRangeCondition(probed)

        assertTrue(rangeCondition != null, message = "hevc must carry a VIDEO_RANGE_TYPE condition")
        val value = rangeCondition!!.value.orEmpty()
        // Dolby Vision must never be advertised as direct-playable.
        assertFalse(value.contains("DOVI"), message = "DV ranges leaked into direct play: $value")
        // Regular HDR/SDR ranges stay direct-playable so they are not needlessly transcoded.
        assertTrue(value.contains("HDR10"))
        assertTrue(value.contains("SDR"))
    }

    @Test
    fun `hevc is still range-restricted when the decoder reports no profiles`() {
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("hevc" to emptySet()),
                audioCodecs = setOf("aac"),
            )

        assertTrue(
            hevcRangeCondition(probed) != null,
            message = "DV must be excluded even with no enumerable hevc profiles",
        )
    }

    @Test
    fun `codecs that cannot carry Dolby Vision are not range restricted`() {
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("h264" to setOf("high", "main")),
                audioCodecs = setOf("aac"),
            )

        val h264RangeConditions =
            JellyfinDeviceProfiles.buildDeviceProfile(probed)
                .codecProfiles
                .filter { it.codec == "h264" }
                .flatMap { it.conditions }
                .filter { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }

        assertEquals(emptyList<Any>(), h264RangeConditions)
    }

    @Test
    fun `device profile direct-plays supported codecs and offers an HLS transcode`() {
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("h264" to setOf("high"), "hevc" to setOf("Main 10")),
                audioCodecs = setOf("aac"),
            )

        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probed)

        assertTrue(
            profile.directPlayProfiles.any { it.videoCodec?.contains("hevc") == true },
            message = "hevc should be a direct-play video codec",
        )
        assertTrue(
            profile.transcodingProfiles.any {
                it.videoCodec?.startsWith("h264") == true && it.protocol == MediaStreamProtocol.HLS
            },
            message = "an h264 HLS transcoding profile is required for the DV fallback",
        )
    }

    private fun hlsProfile(profile: DeviceProfile) =
        profile.transcodingProfiles.single { it.protocol == MediaStreamProtocol.HLS }

    @Test
    fun `e-ac-3 in mp4 is direct-played`() {
        // Media3 reads E-AC-3 from mp4. Left out, every such file went through the server.
        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(
                ProbedCodecs(
                    videoCodecProfiles = mapOf("h264" to setOf("high")),
                    audioCodecs = setOf("aac"),
                )
            )

        for (container in listOf("mp4", "fmp4", "mkv", "mpegts")) {
            val audioCodecs =
                profile.directPlayProfiles
                    .first { it.container == container && it.videoCodec != null }
                    .audioCodec
                    .orEmpty()
                    .split(",")
            assertTrue(
                "eac3" in audioCodecs,
                message = "eac3 missing from $container: $audioCodecs",
            )
        }
    }

    @Test
    fun `the HLS transcode copies hevc when the device decodes it and still encodes to h264`() {
        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(
                ProbedCodecs(
                    videoCodecProfiles =
                        mapOf("h264" to setOf("high"), "hevc" to setOf("Main", "Main 10")),
                    audioCodecs = setOf("aac"),
                )
            )

        // The server encodes to the first codec and copies any codec in the list.
        assertEquals("h264,hevc", hlsProfile(profile).videoCodec)
    }

    @Test
    fun `the HLS transcode is h264 only without an hevc decoder`() {
        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(
                ProbedCodecs(
                    videoCodecProfiles = mapOf("h264" to setOf("high")),
                    audioCodecs = setOf("aac"),
                )
            )

        assertEquals("h264", hlsProfile(profile).videoCodec)
        assertTrue(profile.codecProfiles.none { it.container == "ts" })
    }

    @Test
    fun `hevc is only copied into the HLS transcode without dolby vision`() {
        // Without a codec profile on the segment container the server copies any HEVC stream,
        // Dolby Vision included, and MPEG-TS hides the Dolby Vision configuration from media3.
        val profile =
            JellyfinDeviceProfiles.buildDeviceProfile(probedWithDolbyVision(5 to uhd60, 8 to uhd60))

        val rangeTypes = rangeTypes(profile, "hevc", container = "ts")
        assertTrue("HDR10" in rangeTypes && "SDR" in rangeTypes)
        assertFalse(
            rangeTypes.any { it.startsWith("DOVI") },
            message = "DV ranges leaked into the HLS copy: $rangeTypes",
        )
        val videoProfiles =
            profile.codecProfiles
                .single { it.codec == "hevc" && it.container == "ts" }
                .conditions
                .first { it.property == ProfileConditionValue.VIDEO_PROFILE }
                .value
        assertEquals("Main 10", videoProfiles)
    }

    @Test
    fun `unsupported codecs are not advertised for direct play`() {
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("h264" to setOf("high")),
                audioCodecs = setOf("aac"),
            )

        val profile = JellyfinDeviceProfiles.buildDeviceProfile(probed)

        assertFalse(
            profile.directPlayProfiles.any { it.videoCodec?.contains("hevc") == true },
            message = "hevc must not be direct-play when the device cannot decode it",
        )
        assertTrue(profile.codecProfiles.none { it.codec == "hevc" })
    }

    @Test
    fun `image-based subtitle formats are deliverable embedded and external`() {
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("h264" to setOf("high")),
                audioCodecs = setOf("aac"),
            )

        val subtitleProfiles = JellyfinDeviceProfiles.buildDeviceProfile(probed).subtitleProfiles

        // PGS (BluRay sup), DVDSub (VobSub), DVB are all decoded natively by media3
        // and must be advertised as deliverable both embedded in the container and as
        // sidecar files, so the server never bitmap-transcodes them to text. Encode is
        // the burn-in fallback when the server can't embed or sidecar.
        for (format in listOf("pgssub", "dvdsub", "dvbsub")) {
            assertTrue(
                subtitleProfiles.any {
                    it.format == format && it.method == SubtitleDeliveryMethod.EMBED
                },
                message = "$format must be deliverable embedded",
            )
            assertTrue(
                subtitleProfiles.any {
                    it.format == format && it.method == SubtitleDeliveryMethod.EXTERNAL
                },
                message = "$format must be deliverable as external sidecar",
            )
            assertTrue(
                subtitleProfiles.any {
                    it.format == format && it.method == SubtitleDeliveryMethod.ENCODE
                },
                message = "$format must have a burn-in (Encode) fallback",
            )
        }
    }

    @Test
    fun `subtitle profiles are pinned to the expected exact set`() {
        // Locks down the exact (format, method) pairs the device profile emits.
        // Catches typos ("pgsub"), accidental drops (srt), and accidental
        // additions that a per-format presence test cannot see.
        val probed =
            ProbedCodecs(
                videoCodecProfiles = mapOf("h264" to setOf("high")),
                audioCodecs = setOf("aac"),
            )

        val asPairs =
            JellyfinDeviceProfiles.buildDeviceProfile(probed)
                .subtitleProfiles
                .map { it.format to it.method }
                .toSet()

        val expected =
            setOf(
                // Embed: text + image formats media3 can decode from the container.
                "dvbsub" to SubtitleDeliveryMethod.EMBED,
                "dvdsub" to SubtitleDeliveryMethod.EMBED,
                "pgssub" to SubtitleDeliveryMethod.EMBED,
                "srt" to SubtitleDeliveryMethod.EMBED,
                "subrip" to SubtitleDeliveryMethod.EMBED,
                "ttml" to SubtitleDeliveryMethod.EMBED,
                "ass" to SubtitleDeliveryMethod.EMBED,
                "ssa" to SubtitleDeliveryMethod.EMBED,
                // External: sidecar formats (text + image where the server can stream them).
                "dvbsub" to SubtitleDeliveryMethod.EXTERNAL,
                "dvdsub" to SubtitleDeliveryMethod.EXTERNAL,
                "pgssub" to SubtitleDeliveryMethod.EXTERNAL,
                "srt" to SubtitleDeliveryMethod.EXTERNAL,
                "subrip" to SubtitleDeliveryMethod.EXTERNAL,
                "ttml" to SubtitleDeliveryMethod.EXTERNAL,
                "vtt" to SubtitleDeliveryMethod.EXTERNAL,
                "webvtt" to SubtitleDeliveryMethod.EXTERNAL,
                "ass" to SubtitleDeliveryMethod.EXTERNAL,
                "ssa" to SubtitleDeliveryMethod.EXTERNAL,
                // Encode: burn-in fallback for image subtitles only.
                "dvbsub" to SubtitleDeliveryMethod.ENCODE,
                "dvdsub" to SubtitleDeliveryMethod.ENCODE,
                "pgssub" to SubtitleDeliveryMethod.ENCODE,
            )

        assertEquals(expected, asPairs)
    }

    @Test
    fun `capped mpv profile delivers image subtitles as files, never burnt in`() {
        // #612: with only srt and ass the server burns the default PGS track into the transcode.
        val profile = JellyfinDeviceProfiles.buildDirectPlayTranscodeFallbackProfile()

        assertTrue(profile.transcodingProfiles.isNotEmpty())
        assertTrue(profile.subtitleProfiles.none { it.method == SubtitleDeliveryMethod.ENCODE })
        for (format in listOf("srt", "ass", "pgssub", "dvdsub", "dvbsub")) {
            assertTrue(
                profile.subtitleProfiles.any {
                    it.format == format && it.method == SubtitleDeliveryMethod.EXTERNAL
                },
                message = "$format must be delivered as a file",
            )
        }
    }

    @Test
    fun `direct-play profile is permissive and never transcodes`() {
        val profile = JellyfinDeviceProfiles.buildDirectPlayProfile()

        assertTrue(
            profile.directPlayProfiles.all { it.container == "" },
            message = "mpv profile must direct-play any container",
        )
        assertEquals(emptyList<Any>(), profile.transcodingProfiles)
        assertEquals(emptyList<Any>(), profile.codecProfiles)
    }

    @Test
    fun `download transcode profile keeps non-DV original but routes DV to a progressive transcode`() {
        val profile = JellyfinDeviceProfiles.buildDownloadTranscodeProfile()

        // Direct-plays every container, so non-DV files download as the original.
        assertTrue(profile.directPlayProfiles.any { it.type.toString().contains("VIDEO", true) })
        assertTrue(profile.directPlayProfiles.all { it.container == "" })

        // DV is excluded from direct play, so the server transcodes it instead.
        val rangeCondition =
            profile.codecProfiles
                .filter { it.codec == "hevc" }
                .flatMap { it.conditions }
                .firstOrNull { it.property == ProfileConditionValue.VIDEO_RANGE_TYPE }
        assertTrue(rangeCondition != null, message = "DV must be excluded from direct play")
        assertFalse(rangeCondition!!.value.orEmpty().contains("DOVI"))

        // The transcode must be a single progressive HTTP stream — DownloadManager
        // cannot reassemble HLS segments.
        val transcode = profile.transcodingProfiles.single()
        assertEquals(MediaStreamProtocol.HTTP, transcode.protocol)
        assertEquals("h264", transcode.videoCodec)
    }

    /** #752: [JellyfinDeviceProfiles.profileFor] picks the profile each caller picked before. */
    private val dvCodecs = probedWithDolbyVision(5 to uhd60)
    private val dvDecoders = DeviceDecoders(dvCodecs, displaySupportsDolbyVision = true)
    private val profiles = JellyfinDeviceProfiles()

    private fun local(engine: PlayerEngineId, directPlayDolbyVision: Boolean = true) =
        PlaybackTarget.LocalPlayer(engine, directPlayDolbyVision)

    @Test
    fun `mpv gets the direct-play profile, with the transcode fallback under a cap`() {
        for (directPlayDolbyVision in listOf(true, false)) {
            val mpv = local(PlayerEngineId.MPV, directPlayDolbyVision)
            assertEquals(
                JellyfinDeviceProfiles.buildDirectPlayProfile(),
                profiles.profileFor(mpv, bitrateCapped = false, decoders = dvDecoders),
            )
            assertEquals(
                JellyfinDeviceProfiles.buildDirectPlayTranscodeFallbackProfile(),
                profiles.profileFor(mpv, bitrateCapped = true, decoders = dvDecoders),
            )
        }
    }

    @Test
    fun `ExoPlayer gets the probed profile, cap or not`() {
        for (bitrateCapped in listOf(false, true)) {
            assertEquals(
                JellyfinDeviceProfiles.buildDeviceProfile(dvCodecs, directPlayDolbyVision = true),
                profiles.profileFor(
                    local(PlayerEngineId.EXOPLAYER),
                    bitrateCapped,
                    decoders = dvDecoders,
                ),
            )
            assertEquals(
                JellyfinDeviceProfiles.buildDeviceProfile(dvCodecs, directPlayDolbyVision = false),
                profiles.profileFor(
                    local(PlayerEngineId.EXOPLAYER, false),
                    bitrateCapped,
                    decoders = dvDecoders,
                ),
            )
        }
    }

    @Test
    fun `ExoPlayer direct-plays Dolby Vision only while the display takes it`() {
        val exo = local(PlayerEngineId.EXOPLAYER)

        assertEquals(
            JellyfinDeviceProfiles.buildDeviceProfile(dvCodecs, directPlayDolbyVision = true),
            profiles.profileFor(exo, bitrateCapped = false, decoders = dvDecoders),
        )
        assertEquals(
            JellyfinDeviceProfiles.buildDeviceProfile(dvCodecs, directPlayDolbyVision = false),
            profiles.profileFor(
                exo,
                bitrateCapped = false,
                decoders = DeviceDecoders(dvCodecs, displaySupportsDolbyVision = false),
            ),
        )
    }

    @Test
    fun `a download gets the download profiles`() {
        for (bitrateCapped in listOf(false, true)) {
            assertEquals(
                JellyfinDeviceProfiles.buildDownloadTranscodeProfile(),
                profiles.profileFor(
                    PlaybackTarget.Download(true),
                    bitrateCapped,
                    decoders = dvDecoders,
                ),
            )
        }
        assertEquals(
            JellyfinDeviceProfiles.buildDirectPlayProfile(),
            profiles.profileFor(
                PlaybackTarget.Download(false),
                bitrateCapped = false,
                decoders = dvDecoders,
            ),
        )
        val capped =
            profiles.profileFor(
                PlaybackTarget.Download(false),
                bitrateCapped = true,
                decoders = dvDecoders,
            )
        assertEquals("Direct play all", capped.name)
        assertEquals(MediaStreamProtocol.HTTP, capped.transcodingProfiles.single().protocol)
        assertTrue(capped.subtitleProfiles.none { it.method == SubtitleDeliveryMethod.ENCODE })
    }

    @Test
    fun `a Cast receiver gets the Chromecast profile, with HEVC when it plays it`() {
        listOf(false, true).forEach { capped ->
            assertEquals(
                "Chromecast Video Profile (H.264)",
                profiles
                    .profileFor(
                        PlaybackTarget.CastReceiver(supportsHevc = false),
                        capped,
                        decoders = dvDecoders,
                    )
                    .name,
            )
            assertEquals(
                "Chromecast Video Profile (HEVC)",
                profiles
                    .profileFor(
                        PlaybackTarget.CastReceiver(supportsHevc = true),
                        capped,
                        decoders = dvDecoders,
                    )
                    .name,
            )
        }
    }
}
