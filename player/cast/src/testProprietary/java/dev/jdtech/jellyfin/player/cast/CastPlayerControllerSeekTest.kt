package dev.jdtech.jellyfin.player.cast

import android.content.SharedPreferences
import dev.jdtech.jellyfin.models.Segment
import dev.jdtech.jellyfin.models.SegmentType.INTRO
import dev.jdtech.jellyfin.models.toItemId
import dev.jdtech.jellyfin.models.toMediaSourceId
import dev.jdtech.jellyfin.player.cast.models.CastAvailability
import dev.jdtech.jellyfin.player.cast.models.CastConnectionState
import dev.jdtech.jellyfin.player.cast.models.CastMediaItem
import dev.jdtech.jellyfin.player.cast.models.CastPlaybackStatus
import dev.jdtech.jellyfin.player.cast.models.CastPlayerState
import dev.jdtech.jellyfin.player.cast.models.Device
import dev.jdtech.jellyfin.player.cast.presentation.CastSegmentSkipper
import dev.jdtech.jellyfin.player.cast.presentation.CastViewModel
import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.local.domain.PlaylistManager
import dev.jdtech.jellyfin.repository.ItemCatalog
import dev.jdtech.jellyfin.repository.PlaybackMedia
import dev.jdtech.jellyfin.repository.PlaybackReports
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.testing.FakeMediaServer
import dev.jdtech.jellyfin.testing.fakeRole
import java.time.Duration
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * #1026: verifies the order of `SEEK` requests sent by [CastPlayerControllerImpl.seekTo] when
 * [CastViewModel] reacts to the optimistic seek position and auto-skips a segment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastPlayerControllerSeekTest {
    private val itemA = "0b0e4e6f1c8a4d1f9a3b2c1d0e9f8a7b".toItemId()
    private val intro = Segment(INTRO, startTicks = 0L, endTicks = 30_000L)
    private val duration = 720_000L

    private lateinit var controller: CastPlayerControllerImpl
    private lateinit var viewModel: CastViewModel
    private val sentSeeks = mutableListOf<Long>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val prefs = MemoryPreferences()
        prefs.edit().putString("pref_player_media_segments_intro_action", "SKIP").apply()
        val appPreferences = AppPreferences(prefs)
        controller =
            CastPlayerControllerImpl(
                context = RuntimeEnvironment.getApplication(),
                mediaServer = FakeMediaServer(),
                catalog = fakeRole<ItemCatalog> { _, _, _ -> null },
                playbackReports = fakeRole<PlaybackReports> { _, _, _ -> Unit },
                sessionManager = FakeSession(),
                playlistManagers = Provider<PlaylistManager> { error("unused in seek test") },
                appPreferences = appPreferences,
            )
        controller.seekSender = { options, onResult ->
            sentSeeks += options.position
            onResult(true)
        }
        viewModel =
            CastViewModel(
                sessionManager = FakeSession(),
                playerController = controller,
                media =
                    fakeRole<PlaybackMedia> { _, method, _ ->
                        if (method.name.startsWith("getSegments")) listOf(intro) else null
                    },
                appPreferences = appPreferences,
                context = RuntimeEnvironment.getApplication(),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun setPlayback(positionMs: Long, status: CastPlaybackStatus) {
        val castItem =
            CastMediaItem(
                PlayerItem(
                    name = "Item",
                    itemId = itemA,
                    mediaSourceId = "source".toMediaSourceId(),
                    playbackPosition = 0L,
                )
            )
        controller.setTestState(
            item = castItem,
            state =
                CastPlayerState(
                    status = status,
                    currentPosition = positionMs,
                    duration = duration,
                    positionItemId = itemA,
                    positionFromSeek = false,
                ),
        )
    }

    @Test
    fun `seeking back into a skip segment while playing sends user seek before skip`() {
        setPlayback(positionMs = 45_000L, status = CastPlaybackStatus.PLAYING)

        viewModel.seekTo(9_461L)

        assertEquals(listOf(9_461L, 30_000L), sentSeeks)
        assertEquals(30_000L, controller.playerState.value.currentPosition)

        // Receiver catches up past SETTLE_MS: no second skip.
        setPlayback(positionMs = 30_000L, status = CastPlaybackStatus.PLAYING)
        ShadowSystemClock.advanceBy(Duration.ofMillis(CastSegmentSkipper.SETTLE_MS))
        setPlayback(positionMs = 35_000L, status = CastPlaybackStatus.PLAYING)

        assertEquals(listOf(9_461L, 30_000L), sentSeeks)
    }

    @Test
    fun `seeking back into a skip segment while paused sends user seek before skip and updates position`() {
        setPlayback(positionMs = 45_000L, status = CastPlaybackStatus.PAUSED)

        viewModel.seekTo(9_461L)

        assertEquals(listOf(9_461L, 30_000L), sentSeeks)
        assertEquals(30_000L, controller.playerState.value.currentPosition)
        assertEquals(30_000L, viewModel.state.value.player.currentPosition)
    }

    private class FakeSession : CastSessionManager {
        override val availability = CastAvailability.AVAILABLE
        override val connectionState = MutableStateFlow(CastConnectionState.CONNECTING)
        override val availableDevices = MutableStateFlow<List<Device>>(emptyList())
        override val connectedDevice = MutableStateFlow<Device?>(null)

        override fun init() {}

        override fun updateDiscovery(activeScan: Boolean) {}

        override fun connect(device: Device) {}

        override fun disconnect() {}

        override fun release() {}
    }

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
