package dev.jdtech.jellyfin.core.presentation.downloader

/** CE (#560): what the download service's notification says about the queue. */
internal sealed interface DownloadPumpNotice {
    /** Something is transferring or about to; [count] is everything still to do. */
    data class Downloading(val count: Int) : DownloadPumpNotice

    /** Nothing transfers: the downloads wait for a network they may use. */
    data object WaitingForNetwork : DownloadPumpNotice

    /** Nothing transfers: the downloads wait for their automatic retry. */
    data object Waiting : DownloadPumpNotice
}

/**
 * The notice for [entries], or null when they hold no work: the notification then keeps what it
 * says until the service stops.
 */
internal fun downloadPumpNotice(entries: List<DownloadQueue.Entry>): DownloadPumpNotice? {
    val work = entries.filter { it.isDownloadWork() }
    if (work.isEmpty()) return null
    val waitsForNetwork = { entry: DownloadQueue.Entry ->
        entry.state is DownloadQueue.EntryState.Paused || entry.waitingForNetwork
    }
    val waitsForRetry = { entry: DownloadQueue.Entry ->
        entry.state is DownloadQueue.EntryState.Pending && entry.retryAt != null
    }
    return when {
        // Running, or pending with nothing to wait for: it starts at the pump's next round.
        work.any { !waitsForNetwork(it) && !waitsForRetry(it) } ->
            DownloadPumpNotice.Downloading(work.size)
        work.any(waitsForNetwork) -> DownloadPumpNotice.WaitingForNetwork
        else -> DownloadPumpNotice.Waiting
    }
}
