package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.domain.userdata.PlayState
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.UserId
import kotlinx.coroutines.CancellationException

/**
 * CE (#281): writes a change to the user data of [itemId] and tells the server with [report].
 *
 * The play state [change] computes is written and its row marked to be synced in one transaction,
 * before [report] asks the server. So the change is not lost when the process dies while the
 * request waits (a server that does not answer takes its whole timeout), and the sync's refresh
 * from the server, which leaves marked rows alone, cannot replace it meanwhile. Only items with a
 * row, the downloads, are written and marked; for any other item this is [report] alone.
 *
 * When [report] reaches the server, the mark is taken off again, but only if this call set it (a
 * row marked before holds an earlier change [report] did not send) and only while the row still
 * holds what was written here (a later change keeps its mark). When [report] fails, the row stays
 * marked and a sync is asked for, once for a run of failures. A cancelled [report] leaves the mark
 * for the sync.
 *
 * Returns whether [report] reached the server. #966: over [DownloadUserData], moved from `data`.
 */
suspend fun writeAndReport(
    userData: DownloadUserData,
    syncRequester: UserDataSyncRequester,
    userId: UserId,
    itemId: ItemId,
    change: (PlayState) -> PlayState,
    report: suspend () -> Unit,
): Boolean {
    val marked = userData.writeAndMarkToBeSynced(userId, itemId, change)
    try {
        report()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Marked again in case a concurrent call that reached the server took the mark off.
        if (marked.marked || userData.markToBeSynced(userId, itemId)) {
            syncRequester.request()
        }
        return false
    }
    val row = marked.written
    if (marked.marked && row != null) {
        userData.clearToBeSynced(userId, itemId, row)
    }
    return true
}
