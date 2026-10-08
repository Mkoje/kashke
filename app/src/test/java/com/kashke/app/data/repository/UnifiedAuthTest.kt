package com.kashke.app.data.repository

import com.kashke.app.data.*
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.Phone
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UnifiedAuthTest {
    private fun user(email: String = "", phone: String = "") = User("auth-uuid", "Member",
        "@user_123456789abcdef0123", phone, email, bio = "", location = "",
        borrowingLimit = 2000, availableLimit = 2000, accountStatus = "ACTIVE", memberSince = "2026-10-05")
    @Test fun detectsAndNormalizesBothIdentifiers() {
        assertEquals(AuthIdentifier(IdentifierType.EMAIL, "jane@example.com"), authIdentifier(" Jane@Example.COM "))
        for (raw in listOf("0715501556", "+254715501556", "254715501556"))
            assertEquals(AuthIdentifier(IdentifierType.PHONE, "+254715501556"), authIdentifier(raw))
        for (raw in listOf("0115501556", "+254115501556", "254115501556"))
            assertEquals(AuthIdentifier(IdentifierType.PHONE, "+254115501556"), authIdentifier(raw))
        for (raw in listOf("", "invalid@", "123", "+15551234567", "0201234567"))
            assertTrue(runCatching { authIdentifier(raw) }.isFailure)
    }
    @Test fun installedSdkEncodesRealPasswordProviders() {
        val email = Email.encodeCredentials { this.email = "jane@example.com"; password = "test-password" }
        val phone = Phone.encodeCredentials { this.phone = "+254715501556"; password = "test-password" }
        assertEquals("jane@example.com", email.getValue("email").jsonPrimitive.content)
        assertEquals("+254715501556", phone.getValue("phone").jsonPrimitive.content)
        assertFalse(phone.containsKey("email"))
        assertEquals("password", Phone.grantType)
        assertEquals("password", Email.grantType)
    }
    @Test fun profileAsksOnlyForMissingContactValues() {
        fun labels(u: User) = profileCompletionFields(u).map { it.first }
        assertEquals(listOf("Full name", "Username", "Phone number"), labels(user(email = "jane@example.com")))
        assertEquals(listOf("Full name", "Username", "Email"), labels(user(phone = "+254715501556")))
        assertEquals(listOf("Full name", "Username"), labels(user("jane@example.com", "+254715501556")))
        assertEquals(listOf("Full name", "Username", "Email", "Phone number"), labels(user()))
        assertEquals("auth-uuid", user().id)
    }
    @Test fun onboardingThresholdAndTrustCopy() {
        for (count in 0..4) assertEquals("Make a few connections to get started.", networkEmptyMessage(count))
        for (count in listOf(5, 6, 20)) assertTrue(networkEmptyMessage(count).startsWith("Your network is quiet"))
        val source = File("src/main/java/com/kashke/app/KashKeApp.kt").readText()
        assertTrue(source.contains("Kash Trust is private."))
        // assertFalse(source.contains("Only you can see these points"))
        assertTrue(source.contains("Text(tr(\"Trust points\")"))
        val data = File("src/main/java/com/kashke/app/data/repository/SupabaseDataRepository.kt").readText()
        assertTrue(data.contains("params(\"p_full_name\" to name, \"p_username\" to username, \"p_email\" to email, \"p_phone\" to cleanPhone)"))
    }
    @Test fun phoneErrorsAndLegacyFallbackStayNarrow() {
        assertEquals("An account already exists with this phone number.",
            AuthFlowStageException("AUTH SIGNUP PHONE", RuntimeException("User already registered")).toAuthMessage("Failed"))
        assertTrue(RuntimeException("invalid_credentials").isInvalidCredentials())
        assertFalse(RuntimeException("network timeout").isInvalidCredentials())
    }
}
