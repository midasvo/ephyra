/*
 * Device profile construction adapted from jellyfin-android
 * (https://github.com/jellyfin/jellyfin-android),
 * file: app/src/main/java/org/jellyfin/mobile/player/deviceprofile/DeviceProfileBuilder.kt
 *
 * jellyfin-android is licensed under GPL-2.0; Findroid is likewise GPL-licensed,
 * so this port is compatible.
 *
 * Findroid-specific changes:
 *  - The MediaCodecList hardware probe (DeviceCodecProbe) is split from the (pure) profile
 *    assembly so the assembly can be unit-tested without a device — see [buildDeviceProfile].
 *  - Dolby Vision is only direct-played for the profiles and levels a decoder advertises,
 *    on a display that supports it (see [buildDeviceProfile]); everything else is left to
 *    a server transcode. Profiles 5 and 10.0 have no fallback layer, so that transcode has
 *    wrong colors, and decoding them without DV support does too. A decoder advertising DV
 *    is still no proof the whole pipeline works, so the user can turn this off (the player
 *    setting that direct-plays Dolby Vision).
 *  - The "direct play everything" profile ([buildDirectPlayProfile]) is kept for the
 *    mpv backend, which software-decodes practically anything.
 */
package dev.jdtech.jellyfin.server.jellyfin

import dev.jdtech.jellyfin.models.PlaybackTarget
import dev.jdtech.jellyfin.models.PlayerEngineId
import dev.jdtech.jellyfin.player.DeviceDecoders
import dev.jdtech.jellyfin.player.DolbyVisionLimit
import dev.jdtech.jellyfin.player.ProbedCodecs
import dev.jdtech.jellyfin.server.jellyfin.cast.Chromecast
import dev.jdtech.jellyfin.server.jellyfin.cast.ChromecastH265
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ContainerProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/**
 * The Jellyfin device profile for each [PlaybackTarget], from what the device can decode (#752).
 */
class JellyfinDeviceProfiles {

    /**
     * The profile for [target]; [bitrateCapped] is whether the request has a bitrate cap, and
     * [decoders] holds what this device decodes right now (#1012).
     *
     * mpv software-decodes practically everything, so it gets the permissive direct-play profile,
     * with the transcode fallback under a cap. ExoPlayer gets the honest, hardware-probed profile,
     * cap or not. A Cast receiver gets a Chromecast profile, with or without HEVC (#767).
     */
    fun profileFor(
        target: PlaybackTarget,
        bitrateCapped: Boolean,
        decoders: DeviceDecoders = DeviceDecoders(),
    ): DeviceProfile =
        when (target) {
            is PlaybackTarget.LocalPlayer ->
                when (target.engine) {
                    PlayerEngineId.MPV ->
                        if (bitrateCapped) getDirectPlayTranscodeFallbackProfile()
                        else getDirectPlayProfile()
                    PlayerEngineId.EXOPLAYER ->
                        getDeviceProfile(target.directPlayDolbyVision, decoders)
                }
            is PlaybackTarget.Download ->
                getDownloadProfile(target.transcodeDolbyVision, bitrateCapped)
            // CE (#767): a receiver tells a sender nothing about its codecs; the caller decides
            // whether it plays HEVC. A cap is sent as the request's maximum bitrate.
            is PlaybackTarget.CastReceiver ->
                if (target.supportsHevc) ChromecastH265.deviceProfile else Chromecast.deviceProfile
        }

    /**
     * Honest, hardware-probed profile for ExoPlayer. Files the device cannot direct-play get a
     * server-side transcode. With [directPlayDolbyVision] off that includes all Dolby Vision,
     * whatever the decoders advertise.
     */
    private fun getDeviceProfile(
        directPlayDolbyVision: Boolean,
        decoders: DeviceDecoders,
    ): DeviceProfile =
        buildDeviceProfile(
            decoders.probed,
            directPlayDolbyVision && decoders.displaySupportsDolbyVision,
        )

    /**
     * Permissive "direct play everything" profile for the mpv backend, which software-decodes
     * practically anything and never needs a transcode.
     */
    private fun getDirectPlayProfile(): DeviceProfile = buildDirectPlayProfile()

    /**
     * The mpv profile used when the user caps the streaming bitrate: still direct-play everything,
     * but with the HLS transcoding profiles attached so the server has a transcode target for files
     * whose bitrate exceeds the cap. Without them the plain direct-play profile leaves the server
     * no way to honor a bitrate limit and playback info returns no usable source.
     */
    private fun getDirectPlayTranscodeFallbackProfile(): DeviceProfile =
        buildDirectPlayTranscodeFallbackProfile()

    /**
     * Profile for the downloader. With [transcodeDolbyVision] off this is just the permissive
     * direct-play profile (downloads stay original). With it on, Dolby Vision is excluded from
     * direct play and a progressive H.264 transcoding profile is offered, so DV files download as a
     * device-compatible copy that plays offline — every other file still downloads as the original.
     *
     * [bitrateCapped] is set when the user picked a download quality below "original": the
     * progressive transcoding profile must then be present even without the DV exclusion, so the
     * server has a transcode target for files whose bitrate exceeds the cap. Files under the cap
     * still direct-download.
     */
    private fun getDownloadProfile(
        transcodeDolbyVision: Boolean,
        bitrateCapped: Boolean = false,
    ): DeviceProfile =
        when {
            transcodeDolbyVision -> buildDownloadTranscodeProfile()
            bitrateCapped ->
                buildDirectPlayProfile()
                    .copy(
                        transcodingProfiles = DOWNLOAD_TRANSCODING_PROFILES,
                        subtitleProfiles = SIDECAR_SUBTITLE_PROFILES,
                    )
            else -> buildDirectPlayProfile()
        }

    companion object {
        /**
         * List of container formats supported by ExoPlayer.
         *
         * IMPORTANT: keep aligned with [AVAILABLE_VIDEO_CODECS] and [AVAILABLE_AUDIO_CODECS] — the
         * three arrays are indexed in parallel.
         */
        private val SUPPORTED_CONTAINER_FORMATS =
            arrayOf(
                "mp4",
                "fmp4",
                "webm",
                "mkv",
                "mp3",
                "ogg",
                "wav",
                "mpegts",
                "flv",
                "aac",
                "flac",
                "3gp",
            )

        private val AVAILABLE_VIDEO_CODECS =
            arrayOf(
                // mp4
                arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
                // fmp4
                arrayOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
                // webm
                arrayOf("vp8", "vp9", "av1"),
                // mkv
                arrayOf(
                    "mpeg1video",
                    "mpeg2video",
                    "h263",
                    "mpeg4",
                    "h264",
                    "hevc",
                    "av1",
                    "vp8",
                    "vp9",
                ),
                // mp3
                emptyArray(),
                // ogg
                emptyArray(),
                // wav
                emptyArray(),
                // mpegts
                arrayOf("mpeg1video", "mpeg2video", "mpeg4", "h264", "hevc"),
                // flv
                arrayOf("mpeg4", "h264"),
                // aac
                emptyArray(),
                // flac
                emptyArray(),
                // 3gp
                arrayOf("h263", "mpeg4", "h264", "hevc"),
            )

        /** PCM codecs supported by ExoPlayer by default. */
        private val PCM_CODECS =
            arrayOf(
                "pcm_s8",
                "pcm_s16be",
                "pcm_s16le",
                "pcm_s24le",
                "pcm_s32le",
                "pcm_f32le",
                "pcm_alaw",
                "pcm_mulaw",
            )

        private val AVAILABLE_AUDIO_CODECS =
            arrayOf(
                // mp4
                arrayOf("mp1", "mp2", "mp3", "aac", "alac", "ac3", "eac3", "opus"),
                // fmp4
                arrayOf("mp3", "aac", "ac3", "eac3"),
                // webm
                arrayOf("vorbis", "opus"),
                // mkv
                arrayOf(
                    *PCM_CODECS,
                    "mp1",
                    "mp2",
                    "mp3",
                    "aac",
                    "vorbis",
                    "opus",
                    "flac",
                    "alac",
                    "ac3",
                    "eac3",
                    "dts",
                    "mlp",
                    "truehd",
                ),
                // mp3
                arrayOf("mp3"),
                // ogg
                arrayOf("vorbis", "opus", "flac"),
                // wav
                PCM_CODECS,
                // mpegts
                arrayOf(
                    *PCM_CODECS,
                    "mp1",
                    "mp2",
                    "mp3",
                    "aac",
                    "ac3",
                    "eac3",
                    "dts",
                    "mlp",
                    "truehd",
                ),
                // flv
                arrayOf("mp3", "aac"),
                // aac
                arrayOf("aac"),
                // flac
                arrayOf("flac"),
                // 3gp
                arrayOf("3gpp", "aac", "flac"),
            )

        /**
         * Audio codecs added to the profile regardless of [MediaCodecList] advertising them — these
         * are handled by decoders bundled with ExoPlayer or its ffmpeg extension (Findroid ships
         * the media3 ffmpeg decoder).
         */
        private val FORCED_AUDIO_CODECS =
            setOf(*PCM_CODECS, "alac", "aac", "ac3", "eac3", "dts", "mlp", "truehd")

        // Image-based subtitle formats decoded natively by media3: PGS (BluRay)
        // and DVB (broadcast) parsers landed in earlier releases, VobSub joined
        // in 1.6.0. Findroid runs media3 1.10.1 so all three are available.
        // Advertised for embed, external, and encode delivery: embed/external
        // let the server skip a bitmap-to-text transcode when it can; encode is
        // the burn-in fallback so the user never gets an empty subtitle track.
        private val EXO_EMBEDDED_SUBTITLES =
            arrayOf("dvbsub", "dvdsub", "pgssub", "srt", "subrip", "ttml", "ass", "ssa")
        private val EXO_EXTERNAL_SUBTITLES =
            arrayOf(
                "dvbsub",
                "dvdsub",
                "pgssub",
                "srt",
                "subrip",
                "ttml",
                "vtt",
                "webvtt",
                "ass",
                "ssa",
            )

        /**
         * Image-based subtitle formats that the server should burn into the video stream when
         * neither embed nor external sidecar delivery is possible. Matches what jellyfin-androidtv
         * and jellyfin-android advertise.
         */
        private val EXO_BURNIN_IMAGE_SUBTITLES = arrayOf("dvbsub", "dvdsub", "pgssub")

        /**
         * Video range types that may be direct-played on any device. Every Dolby Vision range
         * (DOVI, DOVIWith*) is absent: [DOLBY_VISION_RANGE_TYPES] adds those per device. `Unknown`
         * is kept so streams whose range the server cannot classify are not needlessly transcoded.
         */
        private const val DIRECT_PLAY_VIDEO_RANGE_TYPES = "SDR|HDR10|HDR10Plus|HLG|Unknown"

        /**
         * The range types the server gives each Dolby Vision profile, per ffmpeg codec name — see
         * `MediaStream.GetVideoColorRange` in Jellyfin. `DOVIInvalid` (out-of-spec files) is never
         * direct-played.
         */
        private val DOLBY_VISION_RANGE_TYPES =
            mapOf(
                "hevc" to
                    mapOf(
                        5 to listOf("DOVI"),
                        7 to listOf("DOVIWithEL", "DOVIWithELHDR10Plus"),
                        8 to
                            listOf(
                                "DOVIWithHDR10",
                                "DOVIWithHLG",
                                "DOVIWithSDR",
                                "DOVIWithHDR10Plus",
                            ),
                    ),
                "av1" to
                    mapOf(
                        10 to
                            listOf(
                                "DOVI",
                                "DOVIWithHDR10",
                                "DOVIWithHLG",
                                "DOVIWithSDR",
                                "DOVIWithHDR10Plus",
                            )
                    ),
            )

        /**
         * Containers media3 reads the Dolby Vision configuration from. Without it (MPEG-TS, for
         * one) ExoPlayer sees a plain HEVC or AV1 stream and never picks the DV decoder.
         */
        private val DOLBY_VISION_CONTAINERS = setOf("mp4", "fmp4", "webm", "mkv")

        // Bitrate ceilings, taken from jellyfin-web's browserDeviceProfile.js.
        private const val MAX_STREAMING_BITRATE = 120_000_000
        private const val MAX_STATIC_BITRATE = 100_000_000
        private const val MAX_MUSIC_TRANSCODING_BITRATE = 384_000

        /** The container of the HLS transcode's segments. */
        private const val HLS_CONTAINER = "ts"

        /**
         * Video codecs the server may copy into the HLS transcode, on top of the H.264 it encodes
         * to. Each is listed after `h264`: the server encodes to the first codec and copies a
         * stream whose codec is anywhere in the list.
         */
        private val HLS_COPY_VIDEO_CODECS = listOf("hevc")

        private val TRANSCODING_PROFILES = transcodingProfiles(emptyList())

        /**
         * H.264 over HLS, with [copyVideoCodecs] as codecs the server may copy instead of encoding:
         * a file that only needs another container or audio codec then costs the server no video
         * encode.
         */
        private fun transcodingProfiles(copyVideoCodecs: List<String>) =
            listOf(
                TranscodingProfile(
                    type = DlnaProfileType.VIDEO,
                    container = HLS_CONTAINER,
                    videoCodec = (listOf("h264") + copyVideoCodecs).joinToString(","),
                    audioCodec = "mp1,mp2,mp3,aac,ac3,eac3,dts,mlp,truehd",
                    protocol = MediaStreamProtocol.HLS,
                    conditions = emptyList(),
                ),
                TranscodingProfile(
                    type = DlnaProfileType.AUDIO,
                    container = "mp3",
                    videoCodec = "",
                    audioCodec = "mp3",
                    protocol = MediaStreamProtocol.HTTP,
                    conditions = emptyList(),
                ),
            )

        private fun subtitleProfiles(): List<SubtitleProfile> = buildList {
            for (format in EXO_EMBEDDED_SUBTITLES) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EMBED))
            }
            for (format in EXO_EXTERNAL_SUBTITLES) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.EXTERNAL))
            }
            // Burn-in fallback for image subtitles: if the server can neither embed
            // nor sidecar the track, it bakes the bitmaps into the video stream.
            for (format in EXO_BURNIN_IMAGE_SUBTITLES) {
                add(SubtitleProfile(format = format, method = SubtitleDeliveryMethod.ENCODE))
            }
        }

        /**
         * Pure assembly of the honest device profile from a [ProbedCodecs] snapshot. Kept
         * side-effect-free and Android-free so it can be unit-tested.
         */
        internal fun buildDeviceProfile(
            probed: ProbedCodecs,
            directPlayDolbyVision: Boolean = true,
        ): DeviceProfile {
            val dolbyVisionProfiles =
                if (directPlayDolbyVision) probed.dolbyVisionProfiles else emptyMap()
            val containerProfiles = ArrayList<ContainerProfile>()
            val directPlayProfiles = ArrayList<DirectPlayProfile>()
            val codecProfiles = ArrayList<CodecProfile>()

            for (i in SUPPORTED_CONTAINER_FORMATS.indices) {
                val container = SUPPORTED_CONTAINER_FORMATS[i]
                val videoCodecs =
                    AVAILABLE_VIDEO_CODECS[i].filter { codec ->
                        probed.videoCodecProfiles.containsKey(codec)
                    }
                val audioCodecs =
                    AVAILABLE_AUDIO_CODECS[i].filter { codec ->
                        codec in probed.audioCodecs || codec in FORCED_AUDIO_CODECS
                    }

                if (videoCodecs.isNotEmpty()) {
                    containerProfiles.add(
                        ContainerProfile(
                            type = DlnaProfileType.VIDEO,
                            container = container,
                            conditions = emptyList(),
                        )
                    )
                    directPlayProfiles.add(
                        DirectPlayProfile(
                            type = DlnaProfileType.VIDEO,
                            container = container,
                            videoCodec = videoCodecs.joinToString(","),
                            audioCodec = audioCodecs.joinToString(","),
                        )
                    )
                    for (videoCodec in videoCodecs) {
                        val dolbyVision =
                            supportedDolbyVision(container, videoCodec, dolbyVisionProfiles)
                        generateCodecProfile(
                                container,
                                videoCodec,
                                probed.videoCodecProfiles,
                                dolbyVision.keys.flatten(),
                            )
                            ?.let(codecProfiles::add)
                        dolbyVision.mapTo(codecProfiles) { (rangeTypes, limit) ->
                            generateDolbyVisionLimit(container, videoCodec, rangeTypes, limit)
                        }
                    }
                }
                if (audioCodecs.isNotEmpty()) {
                    containerProfiles.add(
                        ContainerProfile(
                            type = DlnaProfileType.AUDIO,
                            container = container,
                            conditions = emptyList(),
                        )
                    )
                    directPlayProfiles.add(
                        DirectPlayProfile(
                            type = DlnaProfileType.AUDIO,
                            container = container,
                            audioCodec = audioCodecs.joinToString(","),
                        )
                    )
                }
            }

            // A stream is only copied into the HLS transcode if the device decodes it, and
            // under the conditions direct play has. No Dolby Vision range: the segments are
            // MPEG-TS, where media3 does not see the Dolby Vision configuration.
            val hlsCopyVideoCodecs = HLS_COPY_VIDEO_CODECS.filter {
                probed.videoCodecProfiles.containsKey(it)
            }
            for (videoCodec in hlsCopyVideoCodecs) {
                generateCodecProfile(
                        HLS_CONTAINER,
                        videoCodec,
                        probed.videoCodecProfiles,
                        emptyList(),
                    )
                    ?.let(codecProfiles::add)
            }

            return DeviceProfile(
                name = "Ephyra Device Profile",
                directPlayProfiles = directPlayProfiles,
                transcodingProfiles = transcodingProfiles(hlsCopyVideoCodecs),
                containerProfiles = containerProfiles,
                codecProfiles = codecProfiles,
                subtitleProfiles = subtitleProfiles(),
                maxStreamingBitrate = MAX_STREAMING_BITRATE,
                maxStaticBitrate = MAX_STATIC_BITRATE,
                musicStreamingTranscodingBitrate = MAX_MUSIC_TRANSCODING_BITRATE,
            )
        }

        /** The Dolby Vision range types this device can direct-play, with their limit. */
        private fun supportedDolbyVision(
            container: String,
            videoCodec: String,
            dolbyVisionProfiles: Map<Int, DolbyVisionLimit>,
        ): Map<List<String>, DolbyVisionLimit> {
            if (container !in DOLBY_VISION_CONTAINERS) return emptyMap()
            return buildMap {
                for ((profile, rangeTypes) in DOLBY_VISION_RANGE_TYPES[videoCodec].orEmpty()) {
                    dolbyVisionProfiles[profile]?.let { limit -> put(rangeTypes, limit) }
                }
            }
        }

        /**
         * Builds the codec profile for one (container, videoCodec) pair. The `VIDEO_PROFILE`
         * condition restricts direct play to decoder-supported profiles; for codecs that can carry
         * Dolby Vision an extra `VIDEO_RANGE_TYPE` condition leaves out every DV range except
         * [dolbyVisionRangeTypes], so the server transcodes those.
         */
        private fun generateCodecProfile(
            container: String,
            videoCodec: String,
            videoCodecProfiles: Map<String, Set<String>>,
            dolbyVisionRangeTypes: List<String>,
        ): CodecProfile? {
            val profilesSet = videoCodecProfiles[videoCodec].orEmpty()
            val isRangeRestricted = videoCodec in DOLBY_VISION_RANGE_TYPES
            if (profilesSet.isEmpty() && !isRangeRestricted) {
                return null
            }

            val conditions = buildList {
                if (profilesSet.isNotEmpty()) {
                    add(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_PROFILE,
                            value = profilesSet.joinToString("|"),
                            isRequired = false,
                        )
                    )
                }
                if (isRangeRestricted) {
                    add(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                            value =
                                (listOf(DIRECT_PLAY_VIDEO_RANGE_TYPES) + dolbyVisionRangeTypes)
                                    .joinToString("|"),
                            isRequired = false,
                        )
                    )
                }
            }

            return CodecProfile(
                type = CodecType.VIDEO,
                container = container,
                codec = videoCodec,
                applyConditions = emptyList(),
                conditions = conditions,
            )
        }

        /**
         * Holds [rangeTypes] to the [limit] of the decoder's Dolby Vision level; the server has no
         * condition for the DV level itself. The apply condition is required so that streams with
         * an `Unknown` range are left out of it.
         */
        private fun generateDolbyVisionLimit(
            container: String,
            videoCodec: String,
            rangeTypes: List<String>,
            limit: DolbyVisionLimit,
        ): CodecProfile =
            CodecProfile(
                type = CodecType.VIDEO,
                container = container,
                codec = videoCodec,
                applyConditions =
                    listOf(
                        ProfileCondition(
                            condition = ProfileConditionType.EQUALS_ANY,
                            property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                            value = rangeTypes.joinToString("|"),
                            isRequired = true,
                        )
                    ),
                conditions =
                    listOf(
                        ProfileCondition(
                            condition = ProfileConditionType.LESS_THAN_EQUAL,
                            property = ProfileConditionValue.WIDTH,
                            value = limit.maxWidth.toString(),
                            isRequired = false,
                        ),
                        ProfileCondition(
                            condition = ProfileConditionType.LESS_THAN_EQUAL,
                            property = ProfileConditionValue.HEIGHT,
                            value = limit.maxHeight.toString(),
                            isRequired = false,
                        ),
                        ProfileCondition(
                            condition = ProfileConditionType.LESS_THAN_EQUAL,
                            property = ProfileConditionValue.VIDEO_FRAMERATE,
                            value = limit.maxFramerate.toString(),
                            isRequired = false,
                        ),
                    ),
            )

        /**
         * The permissive profile: direct-play any container/codec, never transcode. Used for the
         * mpv backend. Mirrors the profile Findroid historically sent.
         */
        internal fun buildDirectPlayProfile(): DeviceProfile =
            DeviceProfile(
                name = "Direct play all",
                maxStreamingBitrate = 1_000_000_000,
                maxStaticBitrate = 1_000_000_000,
                directPlayProfiles =
                    listOf(
                        DirectPlayProfile(type = DlnaProfileType.VIDEO, container = ""),
                        DirectPlayProfile(type = DlnaProfileType.AUDIO, container = ""),
                    ),
                transcodingProfiles = emptyList(),
                containerProfiles = emptyList(),
                codecProfiles = emptyList(),
                subtitleProfiles =
                    listOf(
                        SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                        SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                    ),
            )

        /**
         * CE (#612): mpv's profile under a bitrate cap. The transcode carries the image subtitles
         * as sidecars ([SIDECAR_SUBTITLE_PROFILES]): with only srt and ass the server burns the
         * file's default PGS/VobSub track into the picture, which the subtitle dialog cannot switch
         * off. The app picks the subtitle, not the server.
         */
        internal fun buildDirectPlayTranscodeFallbackProfile(): DeviceProfile =
            buildDirectPlayProfile()
                .copy(
                    transcodingProfiles = TRANSCODING_PROFILES,
                    subtitleProfiles = SIDECAR_SUBTITLE_PROFILES,
                )

        /**
         * Subtitle handling for transcodes the app never wants a subtitle burnt into (downloads,
         * mpv under a bitrate cap): everything is delivered as an external sidecar, image formats
         * included. Without the image entries the server's only option for a PGS/VobSub/DVB track
         * is ENCODE (burn-in). In a download that drags a GPU overlay filter chain (VAAPI +
         * Vulkan/libplacebo) into the transcode, observed to wedge the encoder mid-stream and stall
         * the download indefinitely. In playback the server picks the track itself (#612).
         */
        private val SIDECAR_SUBTITLE_PROFILES =
            listOf(
                SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("pgssub", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("dvdsub", SubtitleDeliveryMethod.EXTERNAL),
                SubtitleProfile("dvbsub", SubtitleDeliveryMethod.EXTERNAL),
            )

        /**
         * The transcoding profile used for downloads. Uses HTTP — a single continuous `.ts` stream
         * — rather than HLS, because the app's single-URL OkHttp downloader writes one contiguous
         * file and cannot reassemble HLS segments. `.ts` is also safe to write progressively (no
         * trailing index to patch in, unlike `.mp4`).
         */
        private val DOWNLOAD_TRANSCODING_PROFILES =
            listOf(
                TranscodingProfile(
                    type = DlnaProfileType.VIDEO,
                    container = "ts",
                    videoCodec = "h264",
                    audioCodec = "aac,ac3,eac3,mp3",
                    protocol = MediaStreamProtocol.HTTP,
                    conditions = emptyList(),
                )
            )

        /**
         * Download profile that forces Dolby Vision through a progressive H.264 transcode while
         * leaving every other file as a direct-play original.
         */
        internal fun buildDownloadTranscodeProfile(): DeviceProfile =
            DeviceProfile(
                name = "Ephyra Download Profile",
                maxStreamingBitrate = 1_000_000_000,
                maxStaticBitrate = 1_000_000_000,
                directPlayProfiles =
                    listOf(
                        DirectPlayProfile(type = DlnaProfileType.VIDEO, container = ""),
                        DirectPlayProfile(type = DlnaProfileType.AUDIO, container = ""),
                    ),
                transcodingProfiles = DOWNLOAD_TRANSCODING_PROFILES,
                containerProfiles = emptyList(),
                // Empty container = match every container: Dolby Vision is excluded from
                // direct play regardless of how the file is muxed, so it transcodes.
                codecProfiles =
                    listOf(
                        CodecProfile(
                            type = CodecType.VIDEO,
                            container = "",
                            codec = "hevc",
                            applyConditions = emptyList(),
                            conditions =
                                listOf(
                                    ProfileCondition(
                                        condition = ProfileConditionType.EQUALS_ANY,
                                        property = ProfileConditionValue.VIDEO_RANGE_TYPE,
                                        value = DIRECT_PLAY_VIDEO_RANGE_TYPES,
                                        isRequired = false,
                                    )
                                ),
                        )
                    ),
                subtitleProfiles = SIDECAR_SUBTITLE_PROFILES,
            )
    }
}
