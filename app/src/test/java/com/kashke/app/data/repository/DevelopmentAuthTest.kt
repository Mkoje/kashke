package com.kashke.app.data.repository

import com.kashke.app.data.registrationPhone
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DevelopmentAuthTest {
    @Test fun signupDetailsAreValidatedAndNormalizedBeforeAuthCreation() {
        val details = registrationDetails(" Jane Doe ", " @Jane_Doe ", "0715501556", " Jane@Example.com ")
        assertEquals("Jane Doe", details.getValue("full_name").jsonPrimitive.content)
        assertEquals("jane_doe", details.getValue("username").jsonPrimitive.content)
        assertEquals("+254715501556", details.getValue("phone").jsonPrimitive.content)
        assertEquals("jane@example.com", details.getValue("profile_email").jsonPrimitive.content)
        for (invalid in listOf(
            listOf("", "jane", "0715501556", "jane@example.com"),
            listOf("Jane", "ab", "0715501556", "jane@example.com"),
            listOf("Jane", "user_123456789abcdef0123", "0715501556", "jane@example.com"),
            listOf("Jane", "jane", "123", "jane@example.com"),
            listOf("Jane", "jane", "0715501556", "invalid")
        )) {
            assertTrue(runCatching { registrationDetails(invalid[0], invalid[1], invalid[2], invalid[3]) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun equivalentMobileFormatsShareIdentifier() {
        for ((formats, digits) in listOf(
            listOf("0715501556", "+254715501556", "254715501556") to "254715501556",
            listOf("0115501556", "+254115501556", "254115501556") to "254115501556"
        )) {
            for (phone in formats) {
                assertEquals("+$digits", registrationPhone(phone))
                assertEquals("$digits@auth.kashke.local", internalAuthEmail(phone))
            }
        }
    }

    @Test fun internalIdentifierIsNotPublicEmail() {
        assertNull(publicAuthEmail(internalAuthEmail("0715501556")))
        assertEquals("person@example.com", publicAuthEmail("person@example.com"))
    }

    @Test fun registrationUsesRealEmailAndLegacyPhoneLoginRemainsAvailable() {
        // Contract check against the actual SDK call sites, without contacting hosted Auth.
        val source = File("src/main/java/com/kashke/app/data/repository/SupabaseRepository.kt").readText()
        val register = source.substringAfter("override suspend fun register(").substringBefore("override suspend fun isUsernameAvailable")
        val login = source.substringAfter("override suspend fun login(").substringBefore("override suspend fun logout")
        assertTrue(register.contains("authIdentifier(identifier)"))
        assertFalse(register.contains("internalAuthEmail"))
        assertTrue(login.contains("internalAuthEmail(target.value)"))
        assertTrue(register.contains("signUpWith(Email)"))
        assertTrue(login.contains("signInWith(Email)"))
        assertTrue(register.contains("signUpWith(Phone)"))
        assertTrue(login.contains("signInWith(Phone)"))
        assertTrue(login.contains("if (!e.isInvalidCredentials()) throw e"))
        assertTrue(register.contains("put(\"phone\""))
    }

    @Test fun signupEmailIsValidatedAndNormalized() {
        assertEquals("jane@example.com", registrationEmail(" Jane@Example.COM "))
        for (email in listOf("", "0715501556", "bad@", "254715501556@auth.kashke.local")) {
            assertTrue(runCatching { registrationEmail(email) }.isFailure)
        }
    }
}
