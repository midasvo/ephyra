package dev.jdtech.jellyfin.repository

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import org.jellyfin.sdk.model.api.ServerConfiguration

/**
 * CE (#637): the server's resume settings (Dashboard → Playback → Resume), which decide what a
 * playback stop does to the position and played state. The server applies them to every stop it
 * receives; the app applies the same ones to its local copy, so a download resumes offline where
 * the server would resume it.
 */
data class ResumeThresholds(
    /** A stop before this percentage keeps no position. */
    val minResumePct: Int,
    /** A stop after this percentage marks the item played. */
    val maxResumePct: Int,
    /** An item shorter than this, in seconds, is marked played instead of keeping a position. */
    val minResumeDurationSeconds: Int,
) {
    companion object {
        /** Jellyfin's defaults, used until the server's own are known. */
        val DEFAULT = ResumeThresholds(5, 90, 300)

        fun of(configuration: ServerConfiguration) =
            ResumeThresholds(
                minResumePct = configuration.minResumePct,
                maxResumePct = configuration.maxResumePct,
                minResumeDurationSeconds = configuration.minResumeDurationSeconds,
            )
    }
}

/**
 * CE (#637): the last [ResumeThresholds] read from each server, kept so stops made offline follow
 * them too. Stored per server id, because each server has its own.
 */
class ResumeThresholdsStore(private val preferences: SharedPreferences) {

    fun get(serverId: String?): ResumeThresholds =
        serverId?.let { preferences.getString(key(it), null) }?.let(::parse)
            ?: ResumeThresholds.DEFAULT

    fun put(serverId: String, thresholds: ResumeThresholds) {
        preferences.edit().putString(key(serverId), format(thresholds)).apply()
        readThisProcess.add(serverId)
    }

    /** Whether [serverId]'s were read in this run: they rarely change, so once will do. */
    fun isFresh(serverId: String): Boolean = serverId in readThisProcess

    private fun key(serverId: String) = "ce_resume_thresholds_$serverId"

    internal companion object {
        private val readThisProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun format(thresholds: ResumeThresholds): String =
            with(thresholds) { "$minResumePct,$maxResumePct,$minResumeDurationSeconds" }

        /** Null for a value that is not three numbers, so the defaults are used instead. */
        fun parse(value: String): ResumeThresholds? {
            val parts = value.split(",").map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 3) return null
            return ResumeThresholds(parts[0], parts[1], parts[2])
        }
    }
}
