package app.dandle.ui.dashboard

import app.dandle.data.backend.Backend
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2 (QA 2026-10-31): the sign-out dialog copy is three-way. The old
 * `activeChildIsLocalBackend` (backend != BABYBUDDY) conflated LOCAL and CLOUD, so cloud users
 * saw the device-wipe line that never mentions their cloud copy. Pinned as strings — QA asserts
 * on-screen copy by text, and the exact wording IS the product surface here.
 */
class SignOutCopyTest {

    @Test
    fun `BABYBUDDY sign-out leaves the server copy untouched`() {
        assertEquals(
            "This clears the stored key and local data on this device. Data on the server is untouched.",
            DashboardViewModel.signOutCopyFor(Backend.BABYBUDDY),
        )
    }

    @Test
    fun `LOCAL sign-out is a permanent device wipe`() {
        assertEquals(
            "This permanently clears all data on this device. There is no server copy.",
            DashboardViewModel.signOutCopyFor(Backend.LOCAL),
        )
    }

    @Test
    fun `CLOUD sign-out clears only this device's copy`() {
        assertEquals(
            "This clears this device's copy. Your encrypted data stays safe in Dandle Cloud.",
            DashboardViewModel.signOutCopyFor(Backend.CLOUD),
        )
    }
}
