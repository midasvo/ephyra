package dev.jdtech.jellyfin.settings.presentation.ratings

import android.content.SharedPreferences
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.settings.domain.ExternalRatingsKeyStatus
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** CE (#331): the external ratings settings page stores what it's given and tests only when on. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalRatingsSettingsViewModelTest {
    private val stored = mutableMapOf<String, Any?>()
    private val checked = mutableListOf<String>()
    private var answer = ExternalRatingsKeyStatus.VALID

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        ExternalRatingsSettingsViewModel(AppPreferences(sharedPreferences())) { key ->
            checked += key
            answer
        }

    @Test
    fun `off by default, without a key`() {
        val state = viewModel().state.value
        assertEquals(false, state.enabled)
        assertEquals("", state.apiKey)
    }

    @Test
    fun `the switch and the key are stored, the key without spaces`() {
        val viewModel = viewModel()
        viewModel.onAction(ExternalRatingsSettingsAction.SetEnabled(true))
        viewModel.onAction(ExternalRatingsSettingsAction.SetApiKey(" abc 123\n"))
        assertEquals(true, stored["pref_external_ratings"])
        assertEquals("abc123", stored["pref_mdblist_api_key"])
        viewModel.onAction(ExternalRatingsSettingsAction.SetApiKey(""))
        assertNull(stored["pref_mdblist_api_key"])
    }

    @Test
    fun `Test asks nothing while switched off`() {
        stored["pref_mdblist_api_key"] = "abc"
        val viewModel = viewModel()
        viewModel.onAction(ExternalRatingsSettingsAction.Test)
        assertTrue(checked.isEmpty())
        assertNull(viewModel.state.value.test)
    }

    @Test
    fun `Test shows the answer, and a new key clears it`() {
        stored["pref_external_ratings"] = true
        stored["pref_mdblist_api_key"] = "abc"
        answer = ExternalRatingsKeyStatus.INVALID
        val viewModel = viewModel()
        viewModel.onAction(ExternalRatingsSettingsAction.Test)
        assertEquals(listOf("abc"), checked)
        assertEquals(
            ExternalRatingsTest.Done(ExternalRatingsKeyStatus.INVALID),
            viewModel.state.value.test,
        )
        viewModel.onAction(ExternalRatingsSettingsAction.SetApiKey("abcd"))
        assertNull(viewModel.state.value.test)
    }

    private fun sharedPreferences(): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putBoolean",
                    "putString" -> {
                        stored[args!![0] as String] = args[1]
                        editor
                    }
                    "apply" -> Unit
                    else -> error("not used: ${method.name}")
                }
            } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getBoolean",
                "getString" -> if (args!![0] in stored) stored[args[0]] else args[1]
                "edit" -> editor
                else -> error("not used: ${method.name}")
            }
        } as SharedPreferences
    }
}
