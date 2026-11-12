package dev.jdtech.jellyfin.player.local.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.jdtech.jellyfin.player.core.domain.models.TrickplayTileLoader
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Lazily decodes trickplay sprite-sheets on demand and crops out the right cell for the
 * requested playback position. Sprite-sheets are decoded once and held in an LRU cache,
 * keyed by sheet index, so seeks within a sheet are essentially free and seeks across
 * sheets only re-decode once.
 *
 * The cache is three sheets: the one being scrubbed and the one on either side, which are loaded
 * ahead so crossing a sheet boundary doesn't wait for the network (#615). Sheets are decoded as
 * RGB_565: a sheet of 10x10 thumbnails at 320x180 is 3200x1800 pixels, about 11.5 MB that way and
 * twice that as ARGB_8888, and a JPEG has no alpha to lose.
 */
class TrickplayLoader(
    private val repository: JellyfinRepository,
    private val itemId: UUID,
    private val width: Int,
    private val height: Int,
    tileWidth: Int,
    tileHeight: Int,
    thumbnailCount: Int,
    interval: Int,
    /** Max number of decoded sprite-sheets to keep in memory. */
    cacheSize: Int = DEFAULT_CACHE_SIZE,
) : TrickplayTileLoader {

    private val tiles =
        TrickplayTiles(width, height, tileWidth, tileHeight, thumbnailCount, interval)

    // We deliberately do not call recycle() in entryRemoved. tileAt holds a reference to
    // the evicted bitmap and reads from it on Dispatchers.Default after the LruCache lookup;
    // recycling on the main thread mid-read would crash with
    // "Canvas: trying to use a recycled bitmap". Bitmap pixels are GC-managed, so we let the
    // GC reclaim evicted entries.
    private val sheetCache: LruCache<Int, Bitmap> = LruCache(cacheSize)

    // The loads in flight, by sheet index, so two scrub frames that want the same sheet share one
    // download. A load runs in [loaderScope], not in the caller's job: the seek bar cancels the
    // job of every frame it has moved past, and a download cancelled with it would start again on
    // the next frame, so a sheet could take many attempts to arrive (#615).
    private val loads = ConcurrentHashMap<Int, Deferred<Bitmap?>>()

    /** Runs the sheet loads; cancelled by [release]. */
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val decodeOptions =
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    override suspend fun tileAt(positionMs: Long): Bitmap? {
        val location = tiles.locate(positionMs) ?: return null
        val sheetIndex = location.sheetIndex

        val sheet = loadSheet(sheetIndex) ?: return null
        prefetchNeighbours(sheetIndex)

        // Defensive bounds check — a corrupt/short sheet shouldn't crash the player.
        val offsetX = location.offsetX
        val offsetY = location.offsetY
        if (offsetX + width > sheet.width || offsetY + height > sheet.height) {
            Timber.w(
                "Trickplay tile out of bounds: sheet=$sheetIndex tile=${location.index} " +
                    "offset=($offsetX,$offsetY) sheet=${sheet.width}x${sheet.height}",
            )
            return null
        }

        return withContext(Dispatchers.Default) {
            // createBitmap copies the pixels into a fresh bitmap. The cropped tile is
            // independent of the cached sheet, so it survives sheet eviction.
            Bitmap.createBitmap(sheet, offsetX, offsetY, width, height)
        }
    }

    /**
     * CE (#615): fetches the sheets next to [sheetIndex] in the background. Only called while the
     * user scrubs, so playback that is never scrubbed downloads nothing.
     */
    private fun prefetchNeighbours(sheetIndex: Int) {
        for (neighbour in tiles.neighbourSheets(sheetIndex)) {
            if (sheetCache.get(neighbour) == null) sheetLoad(neighbour).start()
        }
    }

    private suspend fun loadSheet(sheetIndex: Int): Bitmap? {
        sheetCache.get(sheetIndex)?.takeIf { !it.isRecycled }?.let { return it }
        if (sheetIndex < 0 || sheetIndex >= tiles.sheetCount) return null
        return try {
            sheetLoad(sheetIndex).await()
        } catch (e: CancellationException) {
            // The caller was cancelled: pass it on. Otherwise the loader was released.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    /** The load of [sheetIndex] that is in flight, or a new one. Lazy: await or start it. */
    private fun sheetLoad(sheetIndex: Int): Deferred<Bitmap?> =
        loads.computeIfAbsent(sheetIndex) {
            loaderScope.async(start = CoroutineStart.LAZY) {
                try {
                    val bytes = repository.getTrickplayData(itemId, width, sheetIndex)
                    bytes
                        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size, decodeOptions) }
                        ?.also { sheetCache.put(sheetIndex, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load trickplay sheet $sheetIndex")
                    null
                } finally {
                    loads.remove(sheetIndex)
                }
            }
        }

    override fun release() {
        loaderScope.cancel()
        sheetCache.evictAll()
    }

    companion object {
        const val DEFAULT_CACHE_SIZE = 3
    }
}
