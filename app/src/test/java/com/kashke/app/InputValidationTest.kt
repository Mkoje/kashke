package com.kashke.app

import com.kashke.app.data.*
import org.junit.Assert.*
import org.junit.Test

class InputValidationTest {
    @Test fun phoneInputAllowsTypingButRejectsOverlengthAndSymbols() {
        for (number in listOf("0712203900", "+254712203900", "254712203900", "0112203900")) {
            var value = ""
            for (character in number) value = phoneInput(value, value + character)
            assertEquals(number, value)
            assertEquals(number, phoneInput(number, number + "0"))
            assertEquals(number, phoneInput(number, number + "."))
        }
        for (bad in listOf("(0712203900)", "0712.203900", "07122003900", "+2547122039000", "０７１２２０３９００", "0712203900;DROP TABLE wallets")) {
            assertEquals("", phoneInput("", bad))
            assertNull(strictKenyanPhone(bad))
        }
    }
    @Test fun numericFieldsNeverTurnFractionalOrSignedPastesIntoOtherAmounts() {
        for (bad in listOf("1.5", "-1", "1e3", "1,000", "1 OR 1=1", "１２", "12345678901"))
            assertEquals("5", numericInput("5", bad))
        assertEquals("", numericInput("5", ""))
        assertEquals("123", numericInput("", "123"))
    }
    @Test fun emailPolicyChecksSyntaxAndLengths() {
        assertTrue(validEmail("Jane.Doe9@example.co.ke"))
        for (bad in listOf("a..b@example.com", ".a@example.com", "a.@example.com", "a@@example.com", "a@example", "a@x.c", "a@x.123", "a+b@x.com", "a_b@x.com", "a b@x.com")) assertFalse(bad, validEmail(bad))
        assertEquals("", emailInput("", "a'b@example.com"))
        assertEquals("+254712203900", authIdentifierInput("", "+254712203900"))
    }
    @Test fun registrationPasswordsHaveAllFourCategoriesAndBounds() {
        assertTrue(strongRegistrationPassword("Secure9!"))
        assertTrue(strongRegistrationPassword("Secure12345!"))
        for (bad in listOf("secure9!", "SECURE9!", "Secure!!", "Secure99", "Secu9!", "Secure123456!", "Secure9 !")) assertFalse(strongRegistrationPassword(bad))
        assertEquals("Jane Doe", nameInput("", "Jane Doe"))
        assertEquals("Jane", nameInput("Jane", "Jane1"))
    }
}
