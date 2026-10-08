package com.kashke.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

enum class ConnectionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    REMOVED
}

enum class LoanRequestStatus {
    REQUESTED,
    PARTIALLY_FUNDED,
    FULLY_FUNDED,
    ACTIVE,
    DUE_SOON,
    OVERDUE,
    GRACE_PERIOD,
    REPAID,
    CANCELLED,
    RECOVERED_EXTERNALLY,
    DEFAULTED,
    EXPIRED
}

enum class LoanOfferStatus {
    OFFERED,
    TERMS_REQUESTED,
    ACCEPTED,
    DECLINED,
    EXPIRED
}

enum class NotificationType {
    CONNECTION_REQUEST,
    CONNECTION_ACCEPTED,
    CONNECTION_REJECTED,
    NEW_LOAN_REQUEST,
    LOAN_PARTIALLY_FUNDED,
    LOAN_FULLY_FUNDED,
    LOAN_OFFER,
    LOAN_OFFER_TERMS_REQUESTED,
    LOAN_OFFER_UPDATED,
    LOAN_OFFER_CANCELLED,
    LOAN_DEFAULTED,
    LOAN_OFFER_DECLINED,
    LOAN_ACCEPTED,
    LOAN_DUE_SOON,
    LOAN_OVERDUE,
    GRACE_PERIOD_GRANTED,
    LOAN_REPAID,
    TRUST_INCREASED,
    TRUST_DECREASED,
    LIMIT_INCREASED,
    LIMIT_DECREASED,
    BORROWING_FROZEN,
    DIRECT_REQUEST,
    ABOVE_LIMIT_WARNING,
    SUGGESTED_AMOUNT,
    TRUST_UPDATED,
    LOAN_PAYMENT,
    RECOVERED_EXTERNALLY,
    MPESA_DEPOSIT
}

@Serializable
data class User(
    val id: String,
    @SerialName("full_name") val name: String,
    val username: String,
    val phone: String,
    val email: String,
    @SerialName("profile_image") val profileImage: String = "",
    val bio: String,
    val location: String,
    @SerialName("trust_score") var trustScore: Int = 0,
    // Public profiles carry reliability only; private points are owner-only.
    @SerialName("reliability_score") val reliabilityScore: Int? = null,
    @SerialName("reliability_sample_size") val reliabilitySampleSize: Int = 0,
    @SerialName("borrowing_limit") var borrowingLimit: Int,
    @SerialName("available_limit") var availableLimit: Int,
    @SerialName("account_status") var accountStatus: String,
    @SerialName("member_since") val memberSince: String,
    @SerialName("completed_loans") var completedLoans: Int = 0,
    @SerialName("overdue_loans") var overdueLoans: Int = 0,
    @SerialName("repayment_ratio") var repaymentRatio: Int = 0,
    @SerialName("total_borrowed") var totalBorrowed: Int = 0,
    @SerialName("total_repaid") var totalRepaid: Int = 0,
    @SerialName("total_supported") var totalSupported: Int = 0,
    @SerialName("total_earned_from_interest") var totalEarnedFromInterest: Int = 0,
    @SerialName("active_loans") var activeLoans: Int = 0,
    @SerialName("borrowing_frozen") var isBorrowingFrozen: Boolean = false,
    @SerialName("wallet_balance") var walletBalance: Int = 0,
)

@Serializable
data class Connection(
    val id: String,
    @SerialName("requester_id") val requesterId: String,
    @SerialName("recipient_id") val recipientId: String,
    var status: ConnectionStatus,
    @SerialName("created_at") val createdAt: String,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("responded_at") val respondedAt: String? = null
)

@Serializable
data class LoanRequest(
    val id: String,
    @SerialName("borrower_id") val borrowerId: String,
    @SerialName("requested_amount") var requestedAmount: Int,
    @SerialName("funded_amount") var fundedAmount: Int,
    @SerialName("remaining_amount") var remainingAmount: Int,
    var purpose: String,
    var description: String,
    var status: LoanRequestStatus,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("preferred_period_days") var preferredPeriodDays: Int = 30,
    @SerialName("direct_request_to") val directRequestTo: String? = null,
    @SerialName("above_limit_requested") val aboveLimit: Boolean = false,
)

data class DirectRequestBorrowerSummary(
    val borrowerId: String,
    val borrowerName: String,
    val availableLimit: Int,
)

@Serializable
data class LoanOffer(
    val id: String,
    @SerialName("loan_request_id") val loanRequestId: String,
    @SerialName("lender_id") val lenderId: String,
    @SerialName("borrower_id") val borrowerId: String,
    var amount: Int,
    @SerialName("interest_rate") var interestRate: Int,
    @SerialName("repayment_period_days") var repaymentPeriodDays: Int,
    @SerialName("total_repayment") var totalRepayment: Int,
    var status: LoanOfferStatus,
    @SerialName("created_at") val createdAt: String,
    @SerialName("suggested_amount") val suggestedAmount: Int? = null,
    @SerialName("direct_request") val directRequest: Boolean = false,
    @SerialName("requested_interest_rate") val requestedInterestRate: Int? = null,
    @SerialName("requested_repayment_period_days") val requestedRepaymentPeriodDays: Int? = null,
)

@Serializable
data class LoanAgreement(
    val id: String,
    @SerialName("borrower_id") val borrowerId: String,
    @SerialName("lender_id") val lenderId: String,
    @SerialName("loan_request_id") val requestId: String,
    var principal: Int,
    @SerialName("interest_rate") var interestRate: Int,
    @SerialName("interest_amount") var interestAmount: Int,
    @SerialName("total_repayment") var totalRepayment: Int,
    @SerialName("amount_paid") var amountPaid: Int,
    @SerialName("due_date") var dueDate: String,
    var status: LoanRequestStatus,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") var completedAt: String? = null,
    @SerialName("lender_contribution") val lenderContribution: Int = principal,
    @SerialName("original_request_amount") val originalRequestAmount: Int = principal,
    @SerialName("principal_paid") var principalPaid: Int = 0,
    @SerialName("original_due_date") val originalDueDate: String = dueDate,
    @SerialName("grace_granted_at") val graceGrantedAt: String? = null,
    @SerialName("grace_days") val graceDays: Int? = null
)

@Serializable
data class TrustEvent(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: String,
    val points: Int,
    val description: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("related_loan_id") val relatedLoanId: String? = null
)

@Serializable
data class Transaction(
    val id: String,
    val type: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    val amount: Int,
    val interest: Int,
    @SerialName("loan_id") val loanId: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    val description: String
)

@Serializable
data class Notification(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: NotificationType,
    val title: String,
    val message: String,
    var read: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("related_entity_id") val relatedEntityId: String? = null
)
