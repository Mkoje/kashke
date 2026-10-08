package com.kashke.app

import com.kashke.app.data.LoanOffer
import com.kashke.app.data.LoanOfferStatus

internal fun findPendingSupportOffer(
    offers: List<LoanOffer>,
    requestId: String,
    lenderId: String
): LoanOffer? = offers.firstOrNull { offer ->
    offer.loanRequestId == requestId && offer.lenderId == lenderId &&
        offer.status in setOf(LoanOfferStatus.OFFERED, LoanOfferStatus.TERMS_REQUESTED)
}
