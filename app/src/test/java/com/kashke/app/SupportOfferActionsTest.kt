package com.kashke.app

import com.kashke.app.data.LoanOffer
import com.kashke.app.data.LoanOfferStatus
import org.junit.Assert.*
import org.junit.Test

class SupportOfferActionsTest {
    private fun offer(status: LoanOfferStatus = LoanOfferStatus.OFFERED) = LoanOffer(
        id = "offer", loanRequestId = "request", lenderId = "lender", borrowerId = "borrower",
        amount = 2000, interestRate = 7, repaymentPeriodDays = 7, totalRepayment = 2003,
        status = status, createdAt = "2026-10-05T12:00:00Z"
    )

    @Test fun pendingOfferSelectsEditAndCancelForItsRequestAndLender() {
        for (status in listOf(LoanOfferStatus.OFFERED, LoanOfferStatus.TERMS_REQUESTED)) {
            val pending = offer(status)
            assertSame(pending, findPendingSupportOffer(listOf(pending), "request", "lender"))
        }
    }

    @Test fun unrelatedOffersDoNotHideSupport() {
        assertNull(findPendingSupportOffer(listOf(offer()), "another-request", "lender"))
        assertNull(findPendingSupportOffer(listOf(offer()), "request", "another-lender"))
        assertNull(findPendingSupportOffer(emptyList(), "request", "lender"))
    }

    @Test fun closedOffersDoNotCountAsPending() {
        for (status in listOf(LoanOfferStatus.ACCEPTED, LoanOfferStatus.DECLINED, LoanOfferStatus.EXPIRED)) {
            assertNull(findPendingSupportOffer(listOf(offer(status)), "request", "lender"))
        }
    }
}
