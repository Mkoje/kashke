package com.kashke.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public lending context deliberately cannot carry Kash Trust, wallet or email.
 * Keep this separate from User so another person's profile never becomes an
 * accidental personal-account screen. SQL contract: member_profile_details. */
@Serializable
data class MemberProfile(
    val id: String,
    @SerialName("full_name") val name: String,
    val username: String,
    val phone: String,
    val bio: String = "",
    val location: String = "",
    @SerialName("member_since") val memberSince: String,
    @SerialName("reliability_score") val reliabilityScore: Int?,
    @SerialName("reliability_sample_size") val reliabilitySampleSize: Int,
    @SerialName("current_debt_total") val currentDebtTotal: Long,
    @SerialName("current_debt_count") val currentDebtCount: Int,
    @SerialName("borrowing_limit") val borrowingLimit: Long,
    @SerialName("available_limit") val availableLimit: Long,
    @SerialName("previous_transaction_count") val previousTransactionCount: Int,
    @SerialName("borrowing_count") val borrowingCount: Int,
    @SerialName("completed_borrowing_count") val completedBorrowingCount: Int,
    @SerialName("minimum_borrowed") val minimumBorrowed: Long?,
    @SerialName("maximum_borrowed") val maximumBorrowed: Long?,
    @SerialName("average_borrowed") val averageBorrowed: Long?
)

/** An empty/missing session never grants access to the private gauge. */
internal fun canViewPrivateTrust(viewerId: String?, profileId: String): Boolean =
    !viewerId.isNullOrBlank() && profileId.isNotBlank() && viewerId == profileId
