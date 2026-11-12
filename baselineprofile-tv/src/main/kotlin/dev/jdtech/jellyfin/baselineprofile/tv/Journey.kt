package dev.jdtech.jellyfin.baselineprofile.tv

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice

const val PACKAGE_NAME = "nl.midasvo.findroid.ce"

private const val SCREEN_TIMEOUT_MS = 15_000L
private const val FOCUS_TIMEOUT_MS = 3_000L
private const val POLL_MS = 100L

/**
 * Starts the TV app and drives it with the D-pad as far as the device's state allows.
 *
 * TV has no touch and the screens carry no test tags, so the journey only moves focus and waits for
 * the screen to change, instead of finding things by text, which is translated and depends on the
 * server.
 * - Logged in: Home's hero and first row, down every row, open the focused card of the last row,
 *   down to its seasons, open the first season, down its episodes, and back to Home. The show and
 *   season screens are only reached when Home's last row holds shows (a shows library listed last
 *   on the server); with anything else the journey opens that card (and at most the first card
 *   below its buttons) and goes back.
 * - Not logged in: the welcome screen only.
 *
 * Returns true when Home was reached.
 */
fun MacrobenchmarkScope.appJourney(): Boolean {
    pressHome()
    startActivityAndWait()

    // Home focuses its first card once the rows have loaded. The welcome screen focuses a button,
    // so tell them apart by the rows: Home has a scrollable list.
    if (!device.waitFor(SCREEN_TIMEOUT_MS) { hasFocus() && hasObject(By.scrollable(true)) }) {
        return false
    }
    device.waitForIdle()

    // Along the first row and back: the hero follows the focused card.
    repeat(3) { device.dpad { pressDPadRight() } }
    repeat(3) { device.dpad { pressDPadLeft() } }

    // Down every row until the screen stops changing (the last row).
    var rows = 0
    while (rows < 12 && device.dpad { pressDPadDown() }) rows++

    // The focused card -> its screen, where focus lands on the first button. Count the screens
    // opened, so the way back to Home is the same number of Back presses whatever was opened.
    var opened = 0
    if (device.dpad { pressDPadCenter() }) {
        opened++
        // Down to the row under the buttons: a show's seasons. Open the first one and walk its
        // episodes. Any other card (a movie: its cast or related items, or no row at all) is not
        // walked further: the run still ends cleanly on Home, it just profiles less.
        if (device.dpad { pressDPadDown() } && device.dpad { pressDPadCenter() }) {
            opened++
            repeat(3) { device.dpad { pressDPadDown() } }
        }
    }
    repeat(opened) { device.dpad { pressBack() } }
    device.waitForIdle()
    return true
}

/**
 * Presses a key and waits for the screen to change: the focused element or the text on screen.
 * Comparing only the focused element's bounds is not enough, because a TV list scrolls the focused
 * card into the same place on every row. Returns false when nothing changed within
 * [FOCUS_TIMEOUT_MS] (the end of a row or list, or a key the screen ignores).
 */
internal fun UiDevice.dpad(press: UiDevice.() -> Boolean): Boolean {
    val before = snapshot()
    press()
    val changed = waitFor(FOCUS_TIMEOUT_MS) { snapshot().let { it != null && it != before } }
    if (changed) waitForIdle()
    return changed
}

private fun UiDevice.waitFor(timeoutMs: Long, condition: UiDevice.() -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(POLL_MS)
    }
    return false
}

private fun UiDevice.hasFocus(): Boolean = hasObject(By.pkg(PACKAGE_NAME).focused(true))

/** The focused element's bounds and the visible text, or null while the tree is changing. */
private fun UiDevice.snapshot(): String? =
    try {
        val focused = findObject(By.pkg(PACKAGE_NAME).focused(true))?.visibleBounds ?: return null
        val texts = findObjects(By.pkg(PACKAGE_NAME).textContains("")).map { it.text }
        "$focused $texts"
    } catch (_: StaleObjectException) {
        null
    }
