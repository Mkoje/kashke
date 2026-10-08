package com.kashke.app

import com.kashke.app.data.LoanAgreement
import com.kashke.app.data.LoanRequestStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DebtOverviewTest {
    private fun loan(id: String, borrower: String, lender: String, total: Int, paid: Int,
                     status: LoanRequestStatus = LoanRequestStatus.DEFAULTED) =
        LoanAgreement(id, borrower, lender, "request", total, 0, 0, total, paid, "2026-01-01", status, "2026-01-01")

    @Test fun groupsRemainingDebtAndKeepsDirectionsSeparate() {
        val loans = listOf(loan("1", "a", "me", 2000, 500), loan("2", "a", "me", 1000, 200),
            loan("3", "me", "a", 4000, 1000), loan("4", "other", "a", 9000, 0),
            loan("5", "a", "me", 2000, 0, LoanRequestStatus.REPAID),
            loan("6", "a", "me", 2000, 0, LoanRequestStatus.RECOVERED_EXTERNALLY),
            loan("7", "b", "me", 100, 100))
        assertEquals(listOf(PersonDebt("a", 2300, 2, 2300)), personDebts(loans, "me", true))
        assertEquals(listOf(PersonDebt("a", 3000, 1, 3000)), personDebts(loans, "me", false))
    }

    @Test fun totalsUseLongs() {
        val debts = personDebts(listOf(loan("1", "a", "me", Int.MAX_VALUE, 0),
            loan("2", "a", "me", Int.MAX_VALUE, 0)), "me", true)
        assertEquals(4294967294L, debts.single().outstanding)
    }
}
