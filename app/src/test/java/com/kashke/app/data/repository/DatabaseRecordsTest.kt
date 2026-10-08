package com.kashke.app.data.repository

import com.kashke.app.data.*
import com.kashke.app.findPendingSupportOffer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DatabaseRecordsTest {
    @Test fun communityActionsFollowPersistedOfferForCurrentLenderAndRequest() {
        val row = Json.parseToJsonElement("""
            {"id":"offer-id","loan_request_id":"request-id","lender_id":"current-user",
             "borrower_id":"john","amount":1000,"interest_rate":2,"repayment_period_days":30,
             "total_repayment":1002,"status":"OFFERED","created_at":"2026-10-05T12:00:00Z"}
        """).jsonObject
        val saved = DatabaseRecords.offer(row, emptyList())
        assertEquals(saved, findPendingSupportOffer(listOf(saved), "request-id", "current-user"))
        assertNull(findPendingSupportOffer(listOf(saved), "request-id", "another-user"))
        assertNull(findPendingSupportOffer(listOf(saved), "another-request", "current-user"))
        // A fresh backend read after reopening must produce the same action state.
        val reloaded = DatabaseRecords.offer(row, emptyList())
        assertEquals(1000, findPendingSupportOffer(listOf(reloaded), "request-id", "current-user")!!.amount)
        assertEquals(2, reloaded.interestRate)
        val cancelled = DatabaseRecords.offer(JsonObject(row + ("status" to JsonPrimitive("EXPIRED"))), emptyList())
        assertNull(findPendingSupportOffer(listOf(cancelled), "request-id", "current-user"))
    }

    @Test fun realDatabaseRowsDecodeIntoEveryDomainModel() {
        val text = javaClass.getResource("/database_rows.json")!!.readText()
        val fixture = Json.parseToJsonElement(text).jsonObject
        fun rows(table: String) = fixture.getValue(table).jsonArray.map { it.jsonObject }
        val requests = rows("loan_requests").map(DatabaseRecords::request)
        val offers = rows("loan_offers").map { DatabaseRecords.offer(it, requests) }
        val loans = rows("loans").map { DatabaseRecords.loan(it, requests) }
        val users = rows("profiles").map { DatabaseRecords.user(it, "test@example.test", 0) }
        assertTrue(users.all { it.username.startsWith("@") })
        assertTrue(users.any { it.completedLoans == 1 && it.totalBorrowed == 500 })
        assertEquals(LoanRequestStatus.REPAID, loans.first().status)
        assertEquals(1000, loans.first().originalRequestAmount)
        assertEquals(1, loans.first().interestRate)
        assertTrue(offers.any { it.status == LoanOfferStatus.ACCEPTED })
        assertTrue(rows("connections").map(DatabaseRecords::connection).all { it.status == ConnectionStatus.ACCEPTED })
        assertTrue(rows("trust_events").map(DatabaseRecords::event).any { it.type == "OVERDUE" })
        assertTrue(rows("notifications").map(DatabaseRecords::notification).any { it.type == NotificationType.LOAN_PAYMENT })
        assertTrue(rows("transactions").map(DatabaseRecords::transaction).any { it.type == "LOAN_REPAYMENT" && it.interest > 0 })
    }

    @Test fun monetaryConversionRejectsOverflowAndFractionalValues() {
        for (value in listOf("2147483648", "1.25")) {
            try {
                Json.parseToJsonElement("{\"amount\":$value}").jsonObject.number("amount")
                fail("Must not truncate $value")
            } catch (expected: ArithmeticException) { }
        }
        assertEquals(2, Json.parseToJsonElement("{\"interest_rate\":2.00}").jsonObject.number("interest_rate"))
    }

    @Test fun publicDirectoryRequestUsesTheSqlArgumentNamesAndJsonArrayTypes() {
        val profileId = "00000000-0000-0000-0000-000000000001"
        val params = publicProfileDirectoryParams("John", listOf("254712345678"), listOf(profileId))

        assertEquals(setOf("p_query", "p_phone_numbers", "p_profile_ids"), params.keys)
        assertEquals("John", params.getValue("p_query").jsonPrimitive.content)
        assertEquals("254712345678", params.getValue("p_phone_numbers").jsonArray.single().jsonPrimitive.content)
        assertEquals(profileId, params.getValue("p_profile_ids").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun availableSupportBalanceUsesWalletBalanceMinusLockedReserve() {
        assertEquals(800, availableSupportBalance(1000, 200))
        assertEquals(0, availableSupportBalance(200, 500))
    }

    @Test fun safePublicDirectoryProjectionDecodesWithoutPrivateFinancialColumns() {
        val row = Json.parseToJsonElement(
            """{"id":"00000000-0000-0000-0000-000000000001","full_name":"John Kamau","username":"john","phone":"","bio":"","location":"","profile_image":"","reliability_score":66,"reliability_sample_size":3,"account_status":"ACTIVE","created_at":"2026-10-02T00:00:00Z","matched_phone":null}"""
        ).jsonObject
        val user = DatabaseRecords.user(row, "", 0)

        assertEquals("John Kamau", user.name)
        assertEquals("@john", user.username)
        assertEquals(0, user.trustScore)
        assertEquals(66, user.reliabilityScore)
        assertEquals(3, user.reliabilitySampleSize)
        assertEquals(0, user.borrowingLimit)
        assertEquals(0, user.availableLimit)
    }

    @Test fun publicDirectoryContractDecodesRequiredAndOptionalUserFields() {
        val row = Json.parseToJsonElement(
            """{"id":"member-1","full_name":"Member One","username":"member","phone":"+254700000001","bio":"Bio","location":"Nairobi","trust_score":500,"account_status":"ACTIVE","created_at":"2026-10-02T00:00:00Z","matched_phone":"254700000001"}"""
        ).jsonObject

        val user = publicProfileUser(row)
        val match = contactProfileMatch(row)

        assertEquals("Member One", user.name)
        assertEquals("@member", user.username)
        assertEquals("+254700000001", user.phone)
        assertEquals("Bio", user.bio)
        assertEquals("Nairobi", user.location)
        assertEquals(500, user.trustScore)
        assertEquals("", user.profileImage)
        assertEquals(0, user.completedLoans)
        assertEquals(0, user.borrowingLimit)
        assertEquals(0, user.availableLimit)
        assertEquals("ACTIVE", user.accountStatus)
        assertEquals("254700000001", match?.matchedPhone)
        assertEquals(null, contactProfileMatch(Json.parseToJsonElement("""{"id":"member-1"}""").jsonObject))
    }

    @Test fun publicSearchUsesOnlyTheDirectoryQueryArgument() = kotlinx.coroutines.runBlocking {
        var captured: Triple<String, List<String>, List<String>>? = null
        val directory = PublicProfileDirectory { query, phones, ids ->
            captured = Triple(query, phones, ids)
            listOf(publicProfileRow())
        }

        val results = directory.search("  member  ")

        assertEquals(Triple("member", emptyList<String>(), emptyList<String>()), captured)
        assertEquals("member-1", results.single().id)
    }

    @Test fun contactMatchingUsesPhoneNumbersAndRequiresMatchedPhone() = kotlinx.coroutines.runBlocking {
        var captured: Triple<String, List<String>, List<String>>? = null
        val directory = PublicProfileDirectory { query, phones, ids ->
            captured = Triple(query, phones, ids)
            listOf(publicProfileRow(matchedPhone = "254700000001"))
        }

        val results = directory.matchContacts(listOf("254700000001", "254700000001"))

        assertEquals(Triple("", listOf("254700000001"), emptyList<String>()), captured)
        assertEquals("254700000001", results.single().matchedPhone)
    }

    @Test fun publicProfileLookupUsesProfileIds() = kotlinx.coroutines.runBlocking {
        var captured: Triple<String, List<String>, List<String>>? = null
        val directory = PublicProfileDirectory { query, phones, ids ->
            captured = Triple(query, phones, ids)
            listOf(publicProfileRow())
        }

        val result = directory.getProfile("member-1")

        assertEquals(Triple("", emptyList<String>(), listOf("member-1")), captured)
        assertEquals("member-1", result?.id)
    }

    @Test fun publicProfileBatchLookupUsesAllRequestedProfileIds() = kotlinx.coroutines.runBlocking {
        val captured = mutableListOf<Triple<String, List<String>, List<String>>>()
        val directory = PublicProfileDirectory { query, phones, ids ->
            captured += Triple(query, phones, ids)
            emptyList()
        }
        val requestedIds = (1..205).map { "member-$it" } + "member-1"

        val results = directory.getProfiles(requestedIds)

        assertEquals(listOf(100, 100, 5), captured.map { it.third.size })
        assertEquals(requestedIds.distinct(), captured.flatMap { it.third })
        assertTrue(captured.all { it.first.isEmpty() && it.second.isEmpty() })
        assertTrue(results.isEmpty())
    }

    @Test fun connectionStatusContractDecodesSupabaseUppercaseValues() {
        fun decode(status: String) = DatabaseRecords.connection(
            Json.parseToJsonElement(
                """{"id":"connection-1","requester_id":"requester","recipient_id":"recipient","status":"$status","created_at":"2026-10-03T00:00:00Z"}"""
            ).jsonObject
        )

        assertEquals(ConnectionStatus.ACCEPTED, decode("ACCEPTED").status)
        assertEquals(ConnectionStatus.PENDING, decode("PENDING").status)
    }

    private fun publicProfileRow(matchedPhone: String? = null) = Json.parseToJsonElement(
        """{"id":"member-1","full_name":"Member One","username":"member","phone":"+254700000001","bio":"Bio","location":"Nairobi","trust_score":500,"account_status":"ACTIVE","created_at":"2026-10-02T00:00:00Z"${matchedPhone?.let { ",\"matched_phone\":\"$it\"" }.orEmpty()}}"""
    ).jsonObject

    @Test fun rpcDiagnosticsCaptureFunctionContextWithoutLeakingSecrets() {
        val exception = IllegalStateException("public_profile_directory failed; authorization=Bearer secret-token")
        val message = safeDiagnosticMessage(exception, "rpc=public_profile_directory p_phone_numbers=12 p_profile_ids=0")

        assertTrue(message.contains("public_profile_directory"))
        assertTrue(message.contains("p_phone_numbers"))
        assertFalse(message.contains("secret-token"))
    }

    @Test fun diagnosticsRedactTokensPasswordsAndKeys() {
        val message = safeDiagnosticMessage(
            IllegalStateException("access_token=eyJhbGci.eyJyb2xlIn0.signature password=hunter2 apikey=sb_publishable_abc123"),
            "request failed"
        )

        assertFalse(message.contains("eyJhbGci"))
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("sb_publishable_abc123"))
    }
}
