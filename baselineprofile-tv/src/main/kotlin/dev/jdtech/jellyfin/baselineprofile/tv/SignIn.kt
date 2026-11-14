package dev.jdtech.jellyfin.baselineprofile.tv

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signs the profile build in to a server once, so the journey can reach Home. The generation and
 * benchmark builds are a separate install (`nl.midasvo.findroid.ce`, no login), and their data
 * stays as long as the app is not uninstalled. Skipped unless `jfUser` and `jfPassword` are passed
 * as instrumentation arguments; `jfServer` defaults to the emulator's host. See README.md.
 *
 * Like the journey it goes by structure, not by text: a screen with one text field is the server
 * address, with two it is the login; otherwise the focused button (Continue, a server, a user).
 */
@RunWith(AndroidJUnit4::class)
class SignIn {
    @Test
    fun signIn() {
        val args = InstrumentationRegistry.getArguments()
        val user = args.getString("jfUser")
        val password = args.getString("jfPassword")
        assumeTrue("pass jfUser and jfPassword to sign in", user != null && password != null)
        val server = args.getString("jfServer") ?: "http://10.0.2.2:8096"

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.context
        val launch =
            checkNotNull(context.packageManager.getLeanbackLaunchIntentForPackage(PACKAGE_NAME)) {
                "$PACKAGE_NAME is not installed"
            }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).focused(true)), 15_000)) {
            "the app did not start"
        }

        var loginSent = false
        repeat(10) { step ->
            device.waitForIdle()
            // The server and user pickers scroll too, so a list only means Home at the start
            // (already signed in) or after the login.
            if ((step == 0 || loginSent) && device.waitForHome(if (loginSent) 20_000 else 2_000)) {
                Log.i(TAG, "step $step: Home")
                return
            }
            val fields = device.findObjects(By.pkg(PACKAGE_NAME).clazz(EDIT_TEXT))
            when (fields.size) {
                1 -> {
                    Log.i(TAG, "step $step: server address")
                    fields[0].text = server
                    device.submit()
                }
                2 -> {
                    Log.i(TAG, "step $step: login")
                    fields[0].text = user
                    fields[1].text = password
                    device.submit()
                    loginSent = true
                }
                else -> {
                    Log.i(TAG, "step $step: press the focused element")
                    device.dpad { pressDPadCenter() }
                }
            }
            // The next screen: wait for the text fields to go and focus to land.
            device.wait(Until.gone(By.pkg(PACKAGE_NAME).clazz(EDIT_TEXT)), 10_000)
            device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).focused(true)), 10_000)
        }
        error("did not reach Home")
    }

    /**
     * Submits the screen with Enter in the last text field set (its Go action). The on-screen
     * keyboard opens when the text is set and would take the key, so close it first. A touch on the
     * button below does nothing on TV.
     */
    private fun UiDevice.submit() {
        if (executeShellCommand("dumpsys input_method").contains("mInputShown=true")) pressBack()
        waitForIdle()
        pressEnter()
    }

    private companion object {
        const val TAG = "SignIn"
        const val EDIT_TEXT = "android.widget.EditText"
        val focusedList = By.pkg(PACKAGE_NAME).scrollable(true).hasDescendant(By.focused(true))
    }

    /** Home: a list holding the focus, and no text fields (the login screen scrolls as well). */
    private fun UiDevice.waitForHome(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasObject(focusedList) && !hasObject(By.pkg(PACKAGE_NAME).clazz(EDIT_TEXT))) {
                return true
            }
            Thread.sleep(200)
        }
        return false
    }
}
