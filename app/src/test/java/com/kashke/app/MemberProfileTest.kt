package com.kashke.app

import com.kashke.app.data.MemberProfile
import com.kashke.app.data.canViewPrivateTrust
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemberProfileTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun johnsPrivateGaugeIsVisibleOnlyToJohn() {
        assertTrue(canViewPrivateTrust("john", "john"))
        assertFalse(canViewPrivateTrust("immanuel", "john"))
        assertFalse(canViewPrivateTrust(null, "john"))
        assertFalse(canViewPrivateTrust("", "john"))
        assertFalse(canViewPrivateTrust("", ""))
    }

    @Test fun actualPostgresMemberProjectionDecodesWithoutPrivateAccountData() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/database_rows.json")!!.readText()).jsonObject
        val row = fixture.getValue("member_profiles").jsonArray.single().jsonObject
        val member = json.decodeFromJsonElement<MemberProfile>(row)
        assertEquals("borrower", member.name)
        assertEquals(1, member.borrowingCount)
        assertEquals(1, member.completedBorrowingCount)
        assertEquals(500L, member.minimumBorrowed)
        assertEquals(500L, member.maximumBorrowed)
        assertEquals(500L, member.averageBorrowed)
        assertEquals(0L, member.currentDebtTotal)
        assertEquals(100, member.reliabilityScore)
        assertFalse(row.containsKey("trust_score"))
        assertFalse(row.containsKey("wallet_balance"))
        assertFalse(row.containsKey("email"))
    }

    @Test fun largeDebtTotalsAndInconclusiveHistoryRemainExact() {
        val row = Json.parseToJsonElement("""{
            "id":"john","full_name":"John Kamau","username":"john","phone":"0712345678",
            "member_since":"2026-10-04","reliability_score":null,"reliability_sample_size":0,
            "current_debt_total":4000000000,"current_debt_count":2,"borrowing_limit":32000,
            "available_limit":0,"previous_transaction_count":2,"borrowing_count":2,
            "completed_borrowing_count":0,"minimum_borrowed":2000000000,
            "maximum_borrowed":2000000000,"average_borrowed":2000000000
        }""").jsonObject
        val member = json.decodeFromJsonElement<MemberProfile>(row)
        assertEquals(4000000000L, member.currentDebtTotal)
        assertNull(member.reliabilityScore)
        assertEquals(0, member.reliabilitySampleSize)
        // The model's serialized projection cannot turn into a private gauge.
        val encoded = json.encodeToJsonElement(member).jsonObject
        assertFalse(encoded.containsKey("trust_score"))
        assertFalse(encoded.containsKey("wallet_balance"))
        assertFalse(encoded.containsKey("email"))
    }
}
