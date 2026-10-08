package com.kashke.app

import com.kashke.app.data.LoanAgreement
import com.kashke.app.data.LoanRequestStatus
import java.math.BigDecimal

internal const val APP_LOCK_TIMEOUT_MILLIS = 60_000L
internal const val DEFAULT_WALLET_VISIBLE = false

enum class AppSessionState {
    NO_SESSION,
    AUTHENTICATING_WITH_CREDENTIALS,
    AUTHENTICATED_UNLOCKED,
    AUTHENTICATED_LOCKED,
    LOGGED_OUT;

    val isAuthenticated: Boolean
        get() = this == AUTHENTICATED_UNLOCKED || this == AUTHENTICATED_LOCKED

    val isUnlocked: Boolean
        get() = this == AUTHENTICATED_UNLOCKED

    val isLocked: Boolean
        get() = this == AUTHENTICATED_LOCKED

    val isUnauthenticated: Boolean
        get() = this == NO_SESSION || this == LOGGED_OUT

    fun shouldLockFromBackground(elapsedMillis: Long): Boolean =
        isAuthenticated && elapsedMillis >= APP_LOCK_TIMEOUT_MILLIS
}

internal fun shouldLockAfterBackground(elapsedMillis: Long): Boolean =
    elapsedMillis >= APP_LOCK_TIMEOUT_MILLIS

internal fun canShowProtectedContent(profileLoaded: Boolean, appUnlocked: Boolean): Boolean =
    profileLoaded && appUnlocked

internal fun canBiometricUnlock(hasValidSupabaseSession: Boolean): Boolean = hasValidSupabaseSession

internal fun shouldOfferCredentialFallback(biometricsAvailable: Boolean): Boolean = !biometricsAvailable

internal fun canRevealWallet(viewedUserId: String, authenticatedUserId: String): Boolean =
    viewedUserId.isNotBlank() && viewedUserId == authenticatedUserId

internal fun shouldReadContacts(permissionGranted: Boolean): Boolean = permissionGranted

internal fun outstandingLoanBalance(loan: LoanAgreement): Int =
    (loan.totalRepayment - loan.amountPaid).coerceAtLeast(0)

internal fun isActiveLoan(loan: LoanAgreement): Boolean =
    loan.status !in setOf(LoanRequestStatus.REPAID, LoanRequestStatus.RECOVERED_EXTERNALLY) &&
        outstandingLoanBalance(loan) > 0

internal fun isLoanHistoryEntry(loan: LoanAgreement): Boolean =
    loan.status in setOf(LoanRequestStatus.REPAID, LoanRequestStatus.RECOVERED_EXTERNALLY)

internal fun parseWholeKshAmount(input: String): Int? {
    val value = input.trim()
    if (!value.matches(Regex("^[0-9]+(?:\\.[0-9]{1,2})?$"))) return null
    return try {
        BigDecimal(value).stripTrailingZeros().let {
            if (it.scale() > 0) null else it.intValueExact().takeIf { amount -> amount > 0 }
        }
    } catch (_: ArithmeticException) {
        null
    }
}