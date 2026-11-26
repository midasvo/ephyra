package dev.jdtech.jellyfin.player.cast.presentation

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.MediaSourceId
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType.INTRO
import dev.jdtech.jellyfin.models.SegmentType.OUTRO
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.player.cast.CastPlayerController
import dev.jdtech.jellyfin.player.cast.CastSessionManager
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastError
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Track
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.fakeRole
import java.time.Duration
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * #851: how [CastViewModel] wires the segment rule to the receiver: which positions reach it, what
 * its steps do to the controller, and which user actions count as the user's seeks. The rule itself
 * is [CastSegmentSkipperTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastViewModelSegmentTest {
    private val itemA = "0b0e4e6f1c8a4d1f9a3b2c1d0e9f8a7b".toItemId()
    private val itemB = "1c1f5f7a2d9b4e2a8b4c3d2e1f0a9b8c".toItemId()
    private val intro = Segment(INTRO, startTicks = 10_000L, endTicks = 40_000L)
    private val opening = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
    private val duration = 720_000L

    private val controller = FakeController()
    private var segments = listOf(intro)
    /** While true, a segment load waits for [releaseSegments]. */
    private var holdSegments = false
    private val heldLoads = mutableListOf<Continuation<Any?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): CastViewModel {
        val prefs = MemoryPreferences()
        prefs
            .edit()
            .putString("pref_player_media_segments_intro_action", "SKIP")
            .putString("pref_player_media_segments_outro_action", "SKIP")
            .apply()
        return CastViewModel(
            sessionManager = FakeSession(),
            playerController = controller,
            media = media(),
            appPreferences = AppPreferences(prefs),
            context = RuntimeEnvironment.getApplication(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun media(): PlaybackMedia = fakeRole { _, method, args ->
        when {
            method.name.startsWith("getSegments") ->
                if (holdSegments) {
                    heldLoads += args!!.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                } else {
                    segments
                }
            else -> null
        }
    }

    private fun releaseSegments() {
        holdSegments = false
        heldLoads.toList().forEach { it.resumeWith(Result.success(segments)) }
        heldLoads.clear()
    }

    private fun playing(itemId: ItemId, playbackPosition: Long = 0L) {
        controller.currentItem.value =
            CastMediaItem(
                PlayerItem(
                    name = "Item",
                    itemId = itemId,
                    mediaSourceId = "source".toMediaSourceId(),
                    playbackPosition = playbackPosition,
                )
            )
    }

    @Test
    fun `a skip becomes exactly one seek on the receiver`() {
        viewModel()
        playing(itemA)

        controller.report(itemA, 10_000L)
        controller.report(itemA, 11_000L)

        assertEquals(listOf(40_000L), controller.seeks)
    }

    @Test
    fun `a resumed item is not sent back to the intro's end by the stale position`() {
        segments = listOf(opening)
        viewModel()
        playing(itemA, playbackPosition = 600_000L)

        // The state still holds 0 when the receiver starts; then its own first report is the
        // stale 0 too, before the resume position lands.
        controller.report(null, 0L)
        controller.report(itemA, 0L)
        controller.report(itemA, 601_000L)
        controller.report(itemA, 602_000L)

        assertEquals(emptyList(), controller.seeks)
    }

    @Test
    fun `an audio track switch does not send a resumed item back to the intro's end`() {
        segments = listOf(opening)
        val viewModel = viewModel()
        playing(itemA, playbackPosition = 300_000L)
        controller.report(itemA, 300_000L)

        // The switch reloads the same item at 5:00; the first report after it is a stale 0.
        viewModel.setAudioTrack(Track(2, "Nederlands", "nld", "eac3", false, true))
        controller.reload(300_000L)
        controller.report(itemA, 0L)
        controller.report(itemA, 300_000L)
        controller.report(itemA, 301_000L)

        assertEquals(emptyList(), controller.seeks)
    }

    @Test
    fun `a user seek while a load is on its way does not let the stale position skip`() {
        segments = listOf(opening)
        val viewModel = viewModel()
        playing(itemA, playbackPosition = 300_000L)
        controller.report(itemA, 300_000L)

        controller.reload(300_000L)
        viewModel.seekTo(310_000L)
        controller.report(itemA, 0L)
        controller.report(itemA, 310_000L)

        assertEquals(listOf(310_000L), controller.seeks)
    }

    @Test
    fun `the seek target shown at once does not end the wait for a load's position`() {
        // A reload at 5:00; a stale 0; the user seeks and the controller shows 5:10 at once;
        // another stale 0 arrives before the receiver reports the seek.
        segments = listOf(opening)
        val viewModel = viewModel()
        playing(itemA, playbackPosition = 300_000L)
        controller.report(itemA, 300_000L)

        controller.reload(300_000L)
        controller.report(itemA, 0L)
        viewModel.seekTo(310_000L)
        controller.report(itemA, 0L)
        controller.report(itemA, 310_000L)

        assertEquals(listOf(310_000L), controller.seeks)
    }

    @Test
    fun `the moment at 0 at the end of an item is not an entry`() {
        segments = listOf(opening)
        viewModel()
        playing(itemA, playbackPosition = 700_000L)

        controller.report(itemA, 712_000L)
        controller.report(itemA, 718_000L)
        controller.report(itemA, 0L)

        assertEquals(emptyList(), controller.seeks)
    }

    @Test
    fun `positions of the next item do not reach the previous item's segments`() {
        // The queue moved on to an item the controller has to restore first: until then the
        // current item is the previous one, and the positions are the new one's.
        segments = listOf(opening)
        viewModel()
        playing(itemA, playbackPosition = 600_000L)
        controller.report(itemA, 601_000L)

        controller.report(itemB, 0L)
        controller.report(itemB, 1_000L)

        assertEquals(emptyList(), controller.seeks)
    }

    @Test
    fun `a position at 0 with unknown duration after an item runs out does not skip its intro`() {
        // #1027: after skipping the outro to near the end, the receiver transitions to the next
        // queue item and briefly reports position 0 with duration 0 while still naming the old
        // item.
        segments = listOf(opening, Segment(OUTRO, startTicks = 690_000L, endTicks = 730_000L))
        viewModel()
        playing(itemA, playbackPosition = 690_000L)
        controller.report(itemA, 690_000L)

        controller.report(itemA, 0L, durationMs = 0L)
        controller.report(itemA, 1_000L, durationMs = 0L)

        assertEquals(listOf(duration - CastSegmentSkipper.END_MARGIN_MS), controller.seeks)
    }

    @Test
    fun `a load for the next item before currentItem updates does not reset the old item's end guard`() {
        segments = listOf(opening)
        viewModel()
        playing(itemA, playbackPosition = 700_000L)
        controller.report(itemA, 718_000L)

        controller.advanceLoad(itemB, 0L)
        controller.report(itemA, 0L)

        assertEquals(emptyList(), controller.seeks)
    }

    @Test
    fun `a user seek back into the skipped segment skips it again`() {
        val viewModel = viewModel()
        playing(itemA)
        controller.report(itemA, 10_000L)

        viewModel.seekTo(20_000L)
        controller.report(itemA, 20_000L)

        assertEquals(listOf(40_000L, 20_000L, 40_000L), controller.seeks)
    }

    @Test
    fun `a user seek back into a skip segment while paused skips once and updates state`() {
        val viewModel = viewModel()
        playing(itemA)
        controller.report(itemA, 45_000L)
        controller.playerState.update { it.copy(status = CastPlaybackStatus.PAUSED) }

        viewModel.seekTo(20_000L)

        assertEquals(listOf(20_000L, 40_000L), controller.seeks)
        assertEquals(40_000L, viewModel.state.value.player.currentPosition)
    }

    @Test
    fun `a user seek back into a skip segment does not skip again after SETTLE_MS`() {
        val viewModel = viewModel()
        playing(itemA)
        controller.report(itemA, 45_000L)

        viewModel.seekTo(20_000L)
        controller.report(itemA, 40_000L)
        ShadowSystemClock.advanceBy(Duration.ofMillis(CastSegmentSkipper.SETTLE_MS))
        controller.report(itemA, 45_000L)

        assertEquals(listOf(20_000L, 40_000L), controller.seeks)
    }

    @Test
    fun `a user seek elsewhere keeps the guard against a late report`() {
        val viewModel = viewModel()
        playing(itemA)
        controller.report(itemA, 10_000L)

        viewModel.seekBy(30_000L)
        controller.report(itemA, 11_000L) // from before the skip landed
        controller.report(itemA, 71_000L)

        assertEquals(listOf(40_000L, 70_000L), controller.seeks)
    }

    @Test
    fun `previous restarting the item is the user's seek`() {
        val viewModel = viewModel()
        playing(itemA)
        controller.report(itemA, 10_000L)
        controller.report(itemA, 41_000L)

        viewModel.previous()
        controller.report(itemA, 0L)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        controller.report(itemA, 10_000L)

        assertEquals(listOf(40_000L, 0L, 40_000L), controller.seeks)
    }

    @Test
    fun `each item gets its own skip`() {
        viewModel()
        playing(itemA)
        controller.report(itemA, 10_000L)

        playing(itemB)
        controller.report(itemB, 10_000L)

        assertEquals(listOf(40_000L, 40_000L), controller.seeks)
    }

    @Test
    fun `segments that load late still skip`() {
        holdSegments = true
        viewModel()
        playing(itemA)
        controller.report(itemA, 10_000L)
        controller.report(itemA, 11_000L)

        releaseSegments()
        controller.report(itemA, 12_000L)
        controller.report(itemA, 13_000L)

        assertEquals(listOf(40_000L), controller.seeks)
    }

    @Test
    fun `segments of an item that is gone are dropped`() {
        holdSegments = true
        viewModel()
        playing(itemA)
        playing(itemB)

        releaseSegments() // both loads answer; only B's count
        controller.report(itemA, 10_000L)
        controller.report(itemB, 10_000L)

        assertEquals(listOf(40_000L), controller.seeks)
    }

    private inner class FakeController : CastPlayerController {
        override val currentItem = MutableStateFlow<CastMediaItem?>(null)
        override val playerState =
            MutableStateFlow(
                CastPlayerState(status = CastPlaybackStatus.PLAYING, duration = duration)
            )
        override val errors = emptyFlow<CastError>()
        val seeks = mutableListOf<Long>()

        /** The receiver reports [positionMs] for [itemId]. */
        fun report(itemId: ItemId?, positionMs: Long, durationMs: Long = duration) {
            playerState.update {
                it.copy(
                    currentPosition = positionMs,
                    duration = durationMs,
                    positionItemId = itemId,
                    positionFromSeek = false,
                )
            }
        }

        /** The receiver moved on to [itemId] and incremented load before currentItem updated. */
        fun advanceLoad(itemId: ItemId?, positionMs: Long) {
            playerState.update {
                it.copy(
                    load = it.load + 1,
                    loadPositionMs = positionMs,
                    currentPosition = positionMs,
                    positionItemId = itemId,
                    positionFromSeek = false,
                )
            }
        }

        /** A load of the current item goes to the receiver, starting it at [positionMs]. */
        fun reload(positionMs: Long) {
            playerState.update {
                it.copy(load = it.load + 1, loadPositionMs = positionMs, positionItemId = null)
            }
        }

        override fun seekTo(position: Long) {
            seeks += position
            // As the real controller: the target shows at once.
            playerState.update { it.copy(currentPosition = position, positionFromSeek = true) }
        }

        override fun playItem(
            itemId: ItemId,
            itemKind: String,
            startFromBeginning: Boolean,
            mediaSourceId: MediaSourceId?,
            shuffle: Boolean,
        ) {}

        override fun play() {}

        override fun pause() {}

        override fun seekToNext() {}

        override fun seekToPrevious() {}

        override fun setVolume(volume: Float) {}

        override fun setAudioTrack(track: Track?, itemId: ItemId?) {}

        override fun setSubtitleTrack(track: Track?) {}

        override fun stop() {}
    }

    private class FakeSession : CastSessionManager {
        override val availability = CastAvailability.AVAILABLE
        override val connectionState = MutableStateFlow(CastConnectionState.CONNECTED)
        override val availableDevices = MutableStateFlow<List<Device>>(emptyList())
        override val connectedDevice = MutableStateFlow<Device?>(null)

        override fun init() {}

        override fun updateDiscovery(activeScan: Boolean) {}

        override fun connect(device: Device) {}

        override fun disconnect() {}

        override fun release() {}
    }

    /** SharedPreferences in memory. */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()

        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) =
            values[key] as? Set<String> ?: defValues

        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean) =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String) = key in values

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {}

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {}

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { values[key] = value }

            override fun putStringSet(key: String, values: Set<String>?) = apply {
                this@MemoryPreferences.values[key] = values
            }

            override fun putInt(key: String, value: Int) = apply { values[key] = value }

            override fun putLong(key: String, value: Long) = apply { values[key] = value }

            override fun putFloat(key: String, value: Float) = apply { values[key] = value }

            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }

            override fun remove(key: String) = apply { values.remove(key) }

            override fun clear() = apply { values.clear() }

            override fun commit() = true

            override fun apply() {}
        }
    }
}
