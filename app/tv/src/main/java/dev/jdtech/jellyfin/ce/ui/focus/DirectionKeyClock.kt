package dev.jdtech.jellyfin.ce.ui.focus

import android.os.SystemClock
import android.view.KeyEvent

/**
 * CE: when the last D-pad direction key went down, for focus code that tells the user's moves from
 * its own (#533). The activity records every key, also the ones no Compose key modifier sees: while
 * nothing has focus (a cold start, before Home's rows take it) a key moves focus in from outside.
 */
object DirectionKeyClock {
    @Volatile
    private var lastAt = 0L

    fun onKeyEvent(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DirectionKeyCodes) {
            lastAt = SystemClock.uptimeMillis()
        }
    }

    /** A direction key went down less than [millis] ago. */
    fun pressedWithin(millis: Long): Boolean =
        lastAt != 0L && SystemClock.uptimeMillis() - lastAt < millis

    private val DirectionKeyCodes =
        setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
}
