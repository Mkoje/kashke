package com.kashke.app.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import kotlinx.coroutines.runBlocking
import com.kashke.app.data.hasCompleteProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSessionStateTest {
    @Test fun pagingUsesTheActualPrimaryKeyColumnsForTablesWithoutAnId() {
        assertEquals("muted_user_id", repositoryRowOrderColumn("support_request_mutes"))
        assertEquals("user_id", repositoryRowOrderColumn("wallets"))
        assertEquals("id", repositoryRowOrderColumn("connections"))
    }
    @Test fun committedProfileUnlocksRequestsWithoutARefreshAndPreservesAccountMetrics() {
        val before = testUser("current")
        assertFalse(before.hasCompleteProfile())
        val saved = before.withSavedProfile(" Jane Doe ", " Jane_Doe ", " Jane@Example.test ", "+254712345678")
        assertTrue(saved.hasCompleteProfile())
        assertEquals("Jane Doe", saved.name)
        assertEquals("@jane_doe", saved.username)
        assertEquals("jane@example.test", saved.email)
        assertEquals(before, saved.copy(name = before.name, username = before.username, email = before.email, phone = before.phone))
    }

    @Test fun persistedValidSessionRestorationLoadsAuthenticatedRepository() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-123",
            authInitialized = true,
            sessionUserId = "user-123",
            currentUserId = "user-123"
        )

        assertTrue(result.valid)
        assertTrue(result.sessionPresent)
        assertTrue(result.userPresent)
    }

    @Test fun repositoryMustNotUseStalePreRestorationNullSession() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-123",
            authInitialized = false,
            sessionUserId = null,
            currentUserId = null
        )

        assertFalse(result.valid)
        assertFalse(result.sessionPresent)
        assertFalse(result.userPresent)
    }

    @Test fun freshAuthenticatedSessionIsAccepted() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-456",
            authInitialized = true,
            sessionUserId = "user-456",
            currentUserId = "user-456"
        )

        assertTrue(result.valid)
    }

    @Test fun biometricUnlockRequiresAValidRestoredSession() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-789",
            authInitialized = true,
            sessionUserId = "user-789",
            currentUserId = "user-789"
        )

        assertTrue(result.valid)
    }

    @Test fun genuinelyAbsentSessionIsRejected() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-404",
            authInitialized = true,
            sessionUserId = null,
            currentUserId = null
        )

        assertFalse(result.valid)
    }

    @Test fun expiredSessionIsRejected() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-expired",
            authInitialized = true,
            sessionUserId = "user-other",
            currentUserId = "user-other"
        )

        assertFalse(result.valid)
    }

    @Test fun rememberedAccountWithoutBackendSessionStaysLocked() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-remembered",
            authInitialized = true,
            sessionUserId = null,
            currentUserId = null
        )

        assertFalse(result.valid)
    }

    @Test fun actualLogoutOrSessionDestructionIsRejected() {
        val result = evaluateRepositoryAuth(
            expectedUserId = "user-logout",
            authInitialized = true,
            sessionUserId = null,
            currentUserId = null
        )

        assertFalse(result.valid)
    }

    @Test fun authenticatedProfileHydrationUsesOnlyTheFilteredProfilesReader() = runBlocking {
        val profileId = "user-current-profile"
        var selectedId: String? = null
        val ownProfile = Json.parseToJsonElement("""{"id":"$profileId"}""").jsonObject

        val rows = loadAuthenticatedProfileRows(profileId) { requestedId ->
            selectedId = requestedId
            listOf(ownProfile)
        }

        assertEquals(profileId, selectedId)
        assertEquals(listOf(ownProfile), rows)
    }

    @Test fun authenticatedProfileHydrationDoesNotNeedPublicDirectoryResults() = runBlocking {
        val profileId = "user-no-directory"
        val ownProfile = Json.parseToJsonElement(
            """{"id":"$profileId","full_name":"Current User","username":"current","phone":"","bio":"","location":"","trust_score":500,"account_status":"ACTIVE","created_at":"2026-10-02T00:00:00Z"}"""
        ).jsonObject

        val rows = loadAuthenticatedProfileRows(profileId) { listOf(ownProfile) }
        val users = authenticatedProfileUsers(rows, profileId, "current@example.test", 0)

        assertEquals(listOf(ownProfile), rows)
        assertTrue(evaluateProfileHydration(profileId, users).valid)
        assertEquals(profileId, users.single().id)
        assertEquals("Current User", users.single().name)
        assertEquals("current@example.test", users.single().email)
    }

    @Test fun authenticatedProfileHydrationRejectsRowsForOtherUsers() = runBlocking {
        val rows = listOf(
            Json.parseToJsonElement("""{"id":"current"}""").jsonObject,
            Json.parseToJsonElement("""{"id":"other"}""").jsonObject
        )

        val selectedRows = loadAuthenticatedProfileRows("current") { rows }

        assertEquals(listOf("current"), selectedRows.map { it.getValue("id").jsonPrimitive.content })
    }

    @Test fun emptyGenericDirectoryResultIsNotTreatedAsSessionEnded() {
        val hydration = evaluateProfileHydration(
            expectedUserId = "user-empty-success",
            users = emptyList()
        )

        assertFalse(hydration.valid)
        assertFalse(hydration.containsCurrentUser)
        assertEquals(0, hydration.count)
    }

    @Test fun savedContactEmailHydratesForPhoneOnlyOwnerAndStaysPrivate() {
        val row = Json.parseToJsonElement(
            """{"id":"current","full_name":"Current User","username":"current","phone":"+254712345678","profile_email":"contact@example.test","bio":"","location":"","trust_score":0,"account_status":"ACTIVE","created_at":"2026-10-02T00:00:00Z"}"""
        ).jsonObject
        assertEquals("contact@example.test", authenticatedProfileUsers(listOf(row), "current", "", 0).single().email)
        assertEquals("", authenticatedProfileUsers(listOf(row), "other", "other@example.test", 0).single().email)
        assertEquals("", publicProfileUser(row).email)
    }

    @Test fun missingContactEmailUsesCurrentAuthenticatedEmail() {
        val row = Json.parseToJsonElement(
            """{"id":"current","full_name":"Current User","username":"current","phone":"+254712345678","profile_email":"","bio":"","location":"","trust_score":0,"account_status":"ACTIVE","created_at":"2026-10-02T00:00:00Z"}"""
        ).jsonObject
        assertEquals("current@example.test", authenticatedProfileUsers(listOf(row), "current", "current@example.test", 0).single().email)
        assertEquals("", authenticatedProfileUsers(listOf(row), "other", "current@example.test", 0).single().email)
        val emptyPhone = JsonObject(row + ("phone" to JsonPrimitive("")))
        val owner = authenticatedProfileUsers(listOf(emptyPhone), "current", "", 0, "+254715501556").single()
        assertEquals("+254715501556", owner.phone)
        assertEquals("current", owner.id)
    }

    private fun testUser(id: String) = com.kashke.app.data.User(
        id = id,
        name = "Current User",
        username = "@current",
        phone = "",
        email = "",
        bio = "",
        location = "",
        trustScore = 500,
        borrowingLimit = 0,
        availableLimit = 0,
        accountStatus = "ACTIVE",
        memberSince = "2026-10-02"
    )
}
