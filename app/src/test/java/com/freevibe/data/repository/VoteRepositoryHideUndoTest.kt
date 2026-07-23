package com.freevibe.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.freevibe.data.local.PreferencesManager
import com.freevibe.service.CommunityIdentityProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the reversibility the hide/undo snackbar relies on: a non-admin downvote hides an
 * item locally, and undoDownvote restores it. Without this, an accidental Hide is unrecoverable.
 */
@RunWith(RobolectricTestRunner::class)
class VoteRepositoryHideUndoTest {

    private fun newRepository(): VoteRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = mockk<PreferencesManager>(relaxed = true) {
            every { communityProviderEnabled } returns flowOf(true)
            every { communityGuidelinesAccepted } returns flowOf(true)
        }
        val identity = mockk<CommunityIdentityProvider>(relaxed = true)
        val callable = mockk<CommunityCallableClient>(relaxed = true)
        return VoteRepository(context, identity, callable, prefs)
    }

    @Test
    fun `local hide then undoDownvote restores the item to the feed`() = runTest {
        val repo = newRepository()
        val id = "sound-abc123"

        assertTrue("feed starts unhidden", repo.hiddenIds.first().isEmpty())

        repo.hideLocally(id)
        assertFalse("item is hidden after downvote", repo.hiddenIds.first().isEmpty())

        repo.undoDownvote(id)
        assertTrue("undo restores the item", repo.hiddenIds.first().isEmpty())
    }
}
