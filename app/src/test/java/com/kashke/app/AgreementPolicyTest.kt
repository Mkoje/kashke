package com.kashke.app

import com.kashke.app.data.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AgreementPolicyTest {
    private val deadline = Instant.parse("2026-10-10T12:00:00Z")
    private fun loan(status: LoanRequestStatus = LoanRequestStatus.ACTIVE, grace: String? = null) =
        LoanAgreement("loan", "borrower", "lender", "request", 100, 0, 0, 100, 0,
            deadline.toString(), status, "2026-10-01T00:00:00Z", graceGrantedAt = grace)

    @Test fun graceUsesExactThreeDayBoundaries() {
        assertFalse(loan().canGrantGrace(deadline.minusSeconds(259201)))
        assertTrue(loan().canGrantGrace(deadline.minusSeconds(259200)))
        assertTrue(loan(LoanRequestStatus.OVERDUE).canGrantGrace(deadline.plusSeconds(259199)))
        assertFalse(loan(LoanRequestStatus.OVERDUE).canGrantGrace(deadline.plusSeconds(259200)))
    }

    @Test fun settledDefaultedAndPreviouslyExtendedLoansCannotGetGrace() {
        for (status in listOf(LoanRequestStatus.REPAID, LoanRequestStatus.DEFAULTED,
            LoanRequestStatus.RECOVERED_EXTERNALLY, LoanRequestStatus.GRACE_PERIOD)) {
            assertFalse(loan(status).canGrantGrace(deadline))
        }
        assertFalse(loan(grace = "2026-10-09T00:00:00Z").canGrantGrace(deadline))
        assertFalse(loan().copy(amountPaid = 100).canGrantGrace(deadline))
        assertFalse(loan().copy(originalDueDate = "invalid").canGrantGrace(deadline))
    }

    @Test fun localAndInternationalMobileInputsResolveToOneIdentity() {
        for (raw in listOf("0712345678", "+254712345678", "254712345678")) {
            assertEquals("+254712345678", registrationPhone(raw))
        }
        for (raw in listOf("", "123", "+15551234567", "0201234567", "712345678", "+254 712 345 678")) {
            assertTrue(runCatching { registrationPhone(raw) }.isFailure)
        }
        assertEquals(listOf("KE"), supportedPhoneCountries.map { it.iso })
    }

    @Test fun profileCompletionRequiresEveryRequiredField() {
        val complete = User("user", "Jane", "@jane", "+254712345678", "jane@example.test", bio = "",
            location = "Nairobi", borrowingLimit = 2000, availableLimit = 2000,
            accountStatus = "ACTIVE", memberSince = "2026-10-04")
        assertTrue(complete.hasCompleteProfile())
        assertFalse(complete.copy(name = " ").hasCompleteProfile())
        assertFalse(complete.copy(username = "@user_123456789abcdef0123").hasCompleteProfile())
        assertFalse(complete.copy(email = "").hasCompleteProfile())
        assertFalse(complete.copy(email = "invalid").hasCompleteProfile())
        assertFalse(complete.copy(phone = "").hasCompleteProfile())
        assertFalse(complete.copy(phone = "+15551234567").hasCompleteProfile())
        assertTrue(complete.copy(phone = "0115501556").hasCompleteProfile())
        assertTrue(complete.copy(bio = "", location = "").hasCompleteProfile())
        assertFalse(complete.copy(username = "@ab").hasCompleteProfile())
        assertTrue(complete.copy(username = "@user_jane").hasCompleteProfile())
    }
}
