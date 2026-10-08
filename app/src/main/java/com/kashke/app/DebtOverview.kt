package com.kashke.app

import com.kashke.app.data.LoanAgreement
import com.kashke.app.data.LoanRequestStatus

internal data class PersonDebt(val userId: String, val outstanding: Long, val agreements: Int, val defaulted: Long)

internal fun personDebts(loans: List<LoanAgreement>, userId: String, owedToUser: Boolean): List<PersonDebt> =
    loans.filter { isActiveLoan(it) && if (owedToUser) it.lenderId == userId else it.borrowerId == userId }
        .groupBy { if (owedToUser) it.borrowerId else it.lenderId }
        .map { (id, agreements) ->
            PersonDebt(id, agreements.sumOf { outstandingLoanBalance(it).toLong() }, agreements.size,
                agreements.filter { it.status == LoanRequestStatus.DEFAULTED }.sumOf { outstandingLoanBalance(it).toLong() })
        }.sortedWith(compareByDescending<PersonDebt> { it.outstanding }.thenBy { it.userId })
