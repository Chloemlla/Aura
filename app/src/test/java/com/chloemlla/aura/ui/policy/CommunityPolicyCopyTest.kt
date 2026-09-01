package com.chloemlla.aura.ui.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommunityPolicyCopyTest {

    private val resources
        get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `upload policy copy declares public visibility and rights takedown outcomes`() {
        val soundCopy = communityUploadPolicyCopy(resources, CommunityUploadPolicyKind.SOUND)
        val wallpaperCopy = communityUploadPolicyCopy(resources, CommunityUploadPolicyKind.WALLPAPER)

        assertTrue(soundCopy.publicBody.contains("becomes public"))
        assertTrue(soundCopy.publicBody.contains("license"))
        assertTrue(soundCopy.publicBody.contains("public download URL"))
        assertTrue(soundCopy.publicBody.contains("sanitized Storage path"))
        assertTrue(soundCopy.takedownBody.contains("delete your own listing"))
        assertTrue(soundCopy.takedownBody.contains("deletion request code"))
        assertTrue(soundCopy.takedownBody.contains("hosted web request path"))
        assertTrue(soundCopy.takedownBody.contains("hide or delete"))
        assertTrue(soundCopy.takedownBody.contains("uploaded audio file"))
        assertTrue(wallpaperCopy.publicBody.contains("becomes public"))
        assertTrue(wallpaperCopy.takedownBody.contains("uploaded image file"))
    }

    @Test
    fun `attestation copy stays content specific`() {
        assertEquals(
            "I own or have rights to share this sound under the selected license.",
            communityUploadPolicyCopy(resources, CommunityUploadPolicyKind.SOUND).attestation,
        )
        assertEquals(
            "I own or have rights to share this wallpaper under the selected license.",
            communityUploadPolicyCopy(resources, CommunityUploadPolicyKind.WALLPAPER).attestation,
        )
    }

    @Test
    fun `owner delete copy explains public removal and private retention`() {
        val copy = communityOwnerDeleteConfirmationCopy(resources, CommunityUploadPolicyKind.SOUND)

        assertTrue(copy.contains("public listing"))
        assertTrue(copy.contains("owner index"))
        assertTrue(copy.contains("Private deletion or takedown records may remain"))
    }

    @Test
    fun `block copy explains private hiding and no notification`() {
        val copy = communityBlockConfirmationCopy(resources, CommunityUploadPolicyKind.WALLPAPER)

        assertTrue(copy.contains("hides community wallpapers"))
        assertTrue(copy.contains("for your account"))
        assertTrue(copy.contains("not notified"))
    }

    @Test
    fun `report copy directs rights reports to takedown review`() {
        val copy = communityReportTakedownCopy(resources)

        assertTrue(copy.contains("Rights or license"))
        assertTrue(copy.contains("private to admins"))
        assertTrue(copy.contains("hide or delete"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh")
    fun `chinese locale resolves translated policy copy`() {
        val soundCopy = communityUploadPolicyCopy(resources, CommunityUploadPolicyKind.SOUND)

        assertEquals("公开社区列表", soundCopy.publicTitle)
        assertTrue(soundCopy.publicBody.contains("此音效"))
        assertTrue(soundCopy.takedownBody.contains("社区音效及其上传的音频文件"))
        assertTrue(
            communityBlockConfirmationCopy(resources, CommunityUploadPolicyKind.WALLPAPER)
                .contains("隐藏社区壁纸"),
        )
    }
}
