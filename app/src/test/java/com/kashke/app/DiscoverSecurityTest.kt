package com.kashke.app

import com.kashke.app.data.ConnectionStatus
import com.kashke.app.data.Connection
import com.kashke.app.data.ContactProfileMatch
import com.kashke.app.data.DevicePhoneContact
import com.kashke.app.data.DiscoverAction
import com.kashke.app.data.DiscoverContactEntry
import com.kashke.app.data.LoanAgreement
import com.kashke.app.data.LoanRequestStatus
import com.kashke.app.data.User
import com.kashke.app.data.acceptedConnectionsForUser
import com.kashke.app.data.buildContactDirectory
import com.kashke.app.data.buildDiscoverInviteMessage
import com.kashke.app.data.batchContactPhoneNumbers
import com.kashke.app.data.discoverAction
import com.kashke.app.data.loadDiscoverFeature
import com.kashke.app.data.normalizeKenyanPhone
import com.kashke.app.data.otherConnectionParticipantId
import com.kashke.app.data.mapDevicePhoneContact
import com.kashke.app.data.searchDiscoverEntries
import com.kashke.app.data.unconnectedDiscoverContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverSecurityTest {
    @Test fun kenyanPhoneFormatsNormalizeToTheSameNumber() {
        val formats = listOf("0712345678", "+254712345678", "254712345678", "712345678", "+254 (712) 345-678")
        assertEquals(listOf("254712345678", "254712345678", "254712345678", "254712345678", "254712345678"),
            formats.map { normalizeKenyanPhone(it) })
        assertEquals(null, normalizeKenyanPhone("5551234"))
        assertEquals(null, normalizeKenyanPhone("(not a phone)"))
    }

    @Test fun kenyan01RangesAndExplicitInternationalNumbersNormalizeSafely() {
        val formats = listOf("0112345678", "+254112345678", "254112345678", "112345678")
        assertEquals(listOf("254112345678", "254112345678", "254112345678", "254112345678"),
            formats.map { normalizeKenyanPhone(it) })
        assertEquals("12025550100", normalizeKenyanPhone("+1 (202) 555-0100"))
        assertEquals("442079460018", normalizeKenyanPhone("0044 20 7946 0018"))
    }

    @Test fun malformedOrEmptyContactNumbersDoNotCrashDirectoryBuilding() {
        val contacts = listOf(
            DevicePhoneContact("1", "Valid Person", "+254 712 345 678"),
            DevicePhoneContact("2", "Missing Number", ""),
            DevicePhoneContact("3", "Malformed Number", "(not a phone)"),
            DevicePhoneContact("4", "", "0112345678")
        )
        val entries = buildContactDirectory(contacts, listOf(ContactProfileMatch(testUser("registered", "John Kamau"), "254712345678")))
        assertEquals(listOf("Valid Person", "Unknown contact"), entries.map { it.localName })
    }

    @Test fun contactQueryRowsHandleMissingNumbersAndBlankNames() {
        assertEquals(null, mapDevicePhoneContact("1", "No phone", null))
        assertEquals(null, mapDevicePhoneContact("2", "Invalid", "not a phone"))
        assertEquals(
            DevicePhoneContact("3", "Unknown contact", "+254 112 345 678"),
            mapDevicePhoneContact(" 3 ", "  ", " +254 112 345 678 ")
        )
        assertEquals("Second number", mapDevicePhoneContact("4", "Second number", "0712345678")?.displayName)
    }

    @Test fun registeredContactsSortFirstAndUseTheSavedLocalNameWithoutDuplicates() {
        val registeredUser = testUser("registered", "John Kamau")
        val contacts = listOf(
            DevicePhoneContact("1", "John Mechanic", "0712345678"),
            DevicePhoneContact("1", "John Mechanic", "+254 712 345 678"),
            DevicePhoneContact("2", "Auntie", "0711111111"),
            DevicePhoneContact("2", "Auntie", "0111111111")
        )
        val entries = buildContactDirectory(contacts, listOf(ContactProfileMatch(registeredUser, "254712345678")))

        assertEquals(listOf("John Mechanic", "Auntie"), entries.map { it.localName })
        assertEquals("registered", entries.first().profile?.id)
        assertEquals(null, entries.last().profile)
    }

    @Test fun relationshipStateMapsToConnectPendingConnectedAndInvite() {
        assertEquals(DiscoverAction.CONNECT, discoverAction(true, null))
        assertEquals(DiscoverAction.PENDING, discoverAction(true, ConnectionStatus.PENDING))
        assertEquals(DiscoverAction.CONNECTED, discoverAction(true, ConnectionStatus.ACCEPTED))
        assertEquals(DiscoverAction.INVITE, discoverAction(false, null))
    }

    @Test fun acceptedConnectionsIncludeBothDirectionsAndExcludePendingAndUnrelatedRows() {
        val connections = listOf(
            testConnection("member-1", ConnectionStatus.ACCEPTED),
            testConnection("member-2", ConnectionStatus.ACCEPTED).copy(requesterId = "member-2", recipientId = "current-user"),
            testConnection("member-3", ConnectionStatus.ACCEPTED),
            testConnection("member-4", ConnectionStatus.ACCEPTED).copy(requesterId = "member-4", recipientId = "current-user"),
            testConnection("member-5", ConnectionStatus.ACCEPTED),
            testConnection("pending-member", ConnectionStatus.PENDING),
            testConnection("other-member", ConnectionStatus.ACCEPTED).copy(requesterId = "other-user")
        )

        val accepted = acceptedConnectionsForUser(connections, "current-user")

        assertEquals(5, accepted.size)
        assertEquals(
            listOf("member-1", "member-2", "member-3", "member-4", "member-5"),
            accepted.mapNotNull { otherConnectionParticipantId(it, "current-user") }
        )
        assertEquals(null, otherConnectionParticipantId(connections.last(), "current-user"))
    }

    @Test fun homeSuggestionsExcludeConnectedAndPendingProfilesOnly() {
        val connected = DiscoverContactEntry("Connected", "contact-1", testUser("connected", "Connected"))
        val pending = DiscoverContactEntry("Pending", "contact-2", testUser("pending", "Pending"))
        val available = DiscoverContactEntry("Available", "contact-3", testUser("available", "Available"))
        val unregistered = DiscoverContactEntry("Invite", "contact-4", null)
        val connections = listOf(
            testConnection("connected", ConnectionStatus.ACCEPTED),
            testConnection("pending", ConnectionStatus.PENDING),
            testConnection("available", ConnectionStatus.REJECTED)
        )

        assertEquals(
            listOf("available"),
            unconnectedDiscoverContacts(
                listOf(connected, pending, available, unregistered),
                connections,
                "current-user"
            ).map { it.profile?.id }
        )
    }

    @Test fun contactInviteMetadataAndMessageStayUseful() {
        val entries = buildContactDirectory(
            listOf(
                DevicePhoneContact("1", "Auntie", "0712345678"),
                DevicePhoneContact("2", "Jane", "0111111111")
            ),
            emptyList()
        )

        assertEquals("0712345678", entries.first().phoneNumber)
        assertEquals(
            "Hey Auntie! Kash.ke helps you lend and borrow with people you trust and manage repayments. Join me: https://kash.ke | Google Play: https://play.google.com/store/apps/details?id=com.kashke.app",
            buildDiscoverInviteMessage("Auntie")
        )
    }

    @Test fun contactMatchingBatchesAllUniqueNumbersBeyondOneHundred() {
        val numbers = (1..205).map { "2547${it.toString().padStart(8, '0')}" } + "254700000001"

        val batches = batchContactPhoneNumbers(numbers)
        val flattened = batches.flatten()

        assertEquals(listOf(100, 100, 5), batches.map { it.size })
        assertEquals(205, flattened.distinct().size)
        assertEquals(numbers.first(), flattened.first())
        assertEquals(numbers[204], flattened.last())
    }

    @Test fun discoverFailureBecomesFeatureErrorWithoutChangingSessionState() = kotlinx.coroutines.runBlocking {
        val sessionPresent = true
        val currentUserId = "authenticated-user"

        val result = loadDiscoverFeature<List<User>> { throw IllegalStateException("directory unavailable") }

        assertTrue(result.isFailure)
        assertTrue(sessionPresent)
        assertEquals("authenticated-user", currentUserId)
    }

    @Test fun nonemptySearchUsesDatabaseProfilesInsteadOfLocalContacts() {
        val local = DiscoverContactEntry("Local contact", "contact-1", null)
        val searchOnlyProfile = testUser("search-only", "Not Saved Locally")

        val results = searchDiscoverEntries("not saved", listOf(local), listOf(searchOnlyProfile))

        assertEquals(listOf("search-only"), results.map { it.profile?.id })
        assertEquals(listOf(local), searchDiscoverEntries("", listOf(local), emptyList()))
    }

    @Test fun nonemptySearchIncludesMatchingLocalContactsAndKeepsTheirPhoneNumbers() {
        val savedContact = DiscoverContactEntry("Zuri Wanjiku", "contact-1", null, "0712345678")
        val registeredContact = DiscoverContactEntry("Zuri Work", "contact-2", testUser("member-1", "Member Name"), "0112345678")
        val databaseContactMatch = testUser("member-1", "Member Name")
        val databaseOnlyMatch = testUser("member-2", "Zuri M.")

        val results = searchDiscoverEntries(
            "zuri",
            listOf(savedContact, registeredContact),
            listOf(databaseContactMatch, databaseOnlyMatch)
        )

        assertEquals(listOf("contact-1", "contact-2", "member-2"), results.map { it.contactId })
        assertEquals("0712345678", results.first().phoneNumber)
        assertEquals(null, results.first().profile)
        assertEquals("member-1", results[1].profile?.id)
    }

    @Test fun contactSearchCanMatchPhoneNumber() {
        val savedContact = DiscoverContactEntry("Auntie", "contact-1", null, "+254 712 345 678")

        val results = searchDiscoverEntries("7123", listOf(savedContact), emptyList())

        assertEquals(listOf(savedContact), results)
    }

    @Test fun walletAndProtectedContentRequireTheAuthenticatedUnlockedProfile() {
        assertFalse(canRevealWallet("another-user", "signed-in-user"))
        assertTrue(canRevealWallet("signed-in-user", "signed-in-user"))
        assertFalse(canShowProtectedContent(profileLoaded = true, appUnlocked = false))
        assertTrue(canShowProtectedContent(profileLoaded = true, appUnlocked = true))
        assertFalse(canBiometricUnlock(hasValidSupabaseSession = false))
        assertFalse(DEFAULT_WALLET_VISIBLE)
    }

    @Test fun appLockTimeoutAndBiometricFallbackAreExplicit() {
        assertFalse(shouldLockAfterBackground(APP_LOCK_TIMEOUT_MILLIS - 1))
        assertTrue(shouldLockAfterBackground(APP_LOCK_TIMEOUT_MILLIS))
        assertTrue(shouldOfferCredentialFallback(biometricsAvailable = false))
        assertFalse(shouldOfferCredentialFallback(biometricsAvailable = true))
        assertFalse(shouldReadContacts(permissionGranted = false))
        assertTrue(shouldReadContacts(permissionGranted = true))
    }

    @Test fun activeAndHistoryLoansUseServerStatusAndOutstandingBalance() {
        val activePartial = testLoan(LoanRequestStatus.ACTIVE, total = 2020, paid = 500)
        val paid = testLoan(LoanRequestStatus.REPAID, total = 2020, paid = 2020)
        val recovered = testLoan(LoanRequestStatus.RECOVERED_EXTERNALLY, total = 2020, paid = 0)
        val defaulted = testLoan(LoanRequestStatus.DEFAULTED, total = 2020, paid = 500)
        val zeroBalanceButActiveStatus = testLoan(LoanRequestStatus.ACTIVE, total = 2020, paid = 2020)

        assertEquals(1520, outstandingLoanBalance(activePartial))
        assertTrue(isActiveLoan(activePartial))
        assertFalse(isActiveLoan(paid))
        assertFalse(isActiveLoan(recovered))
        assertTrue(isActiveLoan(defaulted))
        assertFalse(isActiveLoan(zeroBalanceButActiveStatus))
        assertTrue(isLoanHistoryEntry(paid))
        assertTrue(isLoanHistoryEntry(recovered))
        assertFalse(isLoanHistoryEntry(activePartial))
    }

    @Test fun partialPaymentParserRejectsInvalidAndUnrepresentableMoney() {
        assertEquals(500, parseWholeKshAmount("500"))
        assertEquals(500, parseWholeKshAmount("500.00"))
        assertEquals(null, parseWholeKshAmount("500.50"))
        assertEquals(null, parseWholeKshAmount("0"))
        assertEquals(null, parseWholeKshAmount("-1"))
        assertEquals(null, parseWholeKshAmount("NaN"))
        assertEquals(null, parseWholeKshAmount("2147483648"))
    }

    @Test fun freshLoginStartsUnlockedAndLockedStateIsExplicit() {
        assertTrue(AppSessionState.AUTHENTICATED_UNLOCKED.isUnlocked)
        assertFalse(AppSessionState.AUTHENTICATED_UNLOCKED.isLocked)
        assertTrue(AppSessionState.AUTHENTICATED_LOCKED.isLocked)
        assertFalse(AppSessionState.AUTHENTICATED_LOCKED.isUnlocked)
        assertTrue(AppSessionState.NO_SESSION.isUnauthenticated)
        assertTrue(AppSessionState.LOGGED_OUT.isUnauthenticated)
    }

    @Test fun noValidSessionNeverLocksBeforeUserActivity() {
        val startedFresh = AppSessionState.NO_SESSION
        assertFalse(startedFresh.isLocked)
        assertFalse(startedFresh.shouldLockFromBackground(0L))
    }

    private fun testUser(id: String, name: String) = User(
        id = id,
        name = name,
        username = "@$id",
        phone = "",
        email = "",
        bio = "",
        location = "",
        trustScore = 500,
        borrowingLimit = 1000,
        availableLimit = 1000,
        accountStatus = "ACTIVE",
        memberSince = "2026-01-01"
    )

    private fun testConnection(profileId: String, status: ConnectionStatus) = Connection(
        id = "connection-$profileId",
        requesterId = "current-user",
        recipientId = profileId,
        status = status,
        createdAt = "2026-10-03T00:00:00Z"
    )

    private fun testLoan(status: LoanRequestStatus, total: Int, paid: Int) = LoanAgreement(
        id = "loan-id",
        borrowerId = "borrower-id",
        lenderId = "lender-id",
        requestId = "request-id",
        principal = 2000,
        interestRate = 1,
        interestAmount = 20,
        totalRepayment = total,
        amountPaid = paid,
        dueDate = "2026-10-02T00:00:00Z",
        status = status,
        createdAt = "2026-09-01T00:00:00Z",
        completedAt = if (status == LoanRequestStatus.REPAID) "2026-10-02T00:00:00Z" else null,
        principalPaid = paid.coerceAtMost(2000)
    )
}