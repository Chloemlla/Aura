package com.chloemlla.aura.ui.policy

import android.content.res.Resources
import androidx.annotation.StringRes
import com.chloemlla.aura.R

enum class CommunityUploadPolicyKind(
    @StringRes val displayNameRes: Int,
    @StringRes val pluralDisplayNameRes: Int,
    @StringRes val publicListingNameRes: Int,
    @StringRes val uploadedFileNameRes: Int,
) {
    SOUND(
        displayNameRes = R.string.policy_kind_sound_singular,
        pluralDisplayNameRes = R.string.policy_kind_sound_plural,
        publicListingNameRes = R.string.policy_kind_sound_public_listing,
        uploadedFileNameRes = R.string.policy_kind_sound_uploaded_file,
    ),
    WALLPAPER(
        displayNameRes = R.string.policy_kind_wallpaper_singular,
        pluralDisplayNameRes = R.string.policy_kind_wallpaper_plural,
        publicListingNameRes = R.string.policy_kind_wallpaper_public_listing,
        uploadedFileNameRes = R.string.policy_kind_wallpaper_uploaded_file,
    ),
    ;

    fun displayName(resources: Resources): String = resources.getString(displayNameRes)

    fun pluralDisplayName(resources: Resources): String = resources.getString(pluralDisplayNameRes)

    fun publicListingName(resources: Resources): String = resources.getString(publicListingNameRes)

    fun uploadedFileName(resources: Resources): String = resources.getString(uploadedFileNameRes)
}

data class CommunityUploadPolicyCopy(
    val publicTitle: String,
    val publicBody: String,
    val takedownBody: String,
    val attestation: String,
)

fun communityUploadPolicyCopy(
    resources: Resources,
    kind: CommunityUploadPolicyKind,
): CommunityUploadPolicyCopy =
    CommunityUploadPolicyCopy(
        publicTitle = resources.getString(R.string.policy_upload_public_title),
        publicBody = resources.getString(
            R.string.policy_upload_public_body,
            kind.displayName(resources),
        ),
        takedownBody = resources.getString(
            R.string.policy_upload_takedown_body,
            kind.publicListingName(resources),
            kind.uploadedFileName(resources),
        ),
        attestation = resources.getString(
            R.string.policy_upload_attestation,
            kind.displayName(resources),
        ),
    )

fun communityOwnerDeleteConfirmationCopy(
    resources: Resources,
    kind: CommunityUploadPolicyKind,
): String =
    resources.getString(
        R.string.policy_owner_delete_confirmation,
        kind.publicListingName(resources),
        kind.uploadedFileName(resources),
    )

fun communityBlockConfirmationCopy(
    resources: Resources,
    kind: CommunityUploadPolicyKind,
): String =
    resources.getString(
        R.string.policy_block_confirmation,
        kind.pluralDisplayName(resources),
    )

fun communityReportTakedownCopy(resources: Resources): String =
    resources.getString(R.string.policy_report_takedown)
