package dev.jdtech.jellyfin.setup.presentation.login

import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.setup.R as SetupR
import dev.jdtech.jellyfin.utils.LoadErrorText
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

/** The Quick Connect call that failed. */
enum class QuickConnectStep {
    /** Asking the server for a code. */
    INITIATE,

    /** Asking whether the code was approved, every few seconds. */
    POLL,

    /** Logging in with the approved code. */
    LOGIN,
}

/**
 * CE (#606): what a failed Quick Connect tells the user, in plain words and never the exception's
 * own text (#377). Failures to reach the server are [LoginViewModel]'s connection text, before
 * this.
 *
 * - The server forgets a code after ten minutes, and a poll for it then answers 404. Logging in
 *   with a code it no longer knows, or that was not approved, is refused.
 * - With Quick Connect turned off, the server refuses the request for a code, and the polls.
 * - Anything else: the server's status, or "Unknown error" ([LoadErrorText.reason]).
 */
fun quickConnectErrorText(error: Throwable, step: QuickConnectStep): UiText =
    when {
        isQuickConnectOff(error, step) ->
            UiText.StringResource(SetupR.string.ce_quick_connect_error_disabled)
        isCodeExpired(error, step) ->
            UiText.StringResource(SetupR.string.ce_quick_connect_error_expired)
        else -> LoadErrorText.reason(error)
    }

/** Whether the server said that Quick Connect is turned off, so the button can go. */
fun isQuickConnectOff(error: Throwable, step: QuickConnectStep): Boolean =
    step != QuickConnectStep.LOGIN && error.httpStatus() in setOf(401, 403)

private fun isCodeExpired(error: Throwable, step: QuickConnectStep): Boolean =
    when (step) {
        QuickConnectStep.INITIATE -> false
        QuickConnectStep.POLL -> error.httpStatus() == 404
        QuickConnectStep.LOGIN -> error.httpStatus() in setOf(400, 401, 403, 404)
    }

private fun Throwable.httpStatus(): Int? =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(16)
        .filterIsInstance<InvalidStatusException>()
        .firstOrNull()
        ?.status
