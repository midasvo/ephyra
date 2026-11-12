package dev.jdtech.jellyfin.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** CE (#552): what a sign of the server being back does to the user data sync. */
class UserDataSyncRestartTest {

    @Test
    fun `nothing waiting starts nothing`() {
        for (state in WorkInfo.State.entries + null) {
            assertNull(restartPolicy(hasPending = false, state = state))
        }
    }

    @Test
    fun `a run that is busy is left alone`() {
        assertNull(restartPolicy(hasPending = true, state = WorkInfo.State.RUNNING))
    }

    @Test
    fun `a retry that is waiting out its delay starts over`() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            restartPolicy(hasPending = true, state = WorkInfo.State.ENQUEUED),
        )
    }

    @Test
    fun `rows waiting without any work start one`() {
        for (state in listOf(null, WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)) {
            assertEquals(ExistingWorkPolicy.REPLACE, restartPolicy(hasPending = true, state = state))
        }
    }
}
