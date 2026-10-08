package com.kashke.app

import com.kashke.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ContactFormTest {
    @Test fun acceptsOrdinaryEnglishAndSwahiliMessages() {
        assertTrue(validContactForm("Anne O'Brien","anne+help@kash.ke","Repayment question","Habari, nina swali kuhusu malipo yangu."))
        assertTrue(validContactForm("Mkoje Emmanuel","info@kash.ke","My account","Line one.\nPlease help with my account."))
    }
    @Test fun rejectsMarkupControlCharactersInvalidAddressesAndOversizedInputs() {
        val base="Please help with my account."
        assertFalse(validContactForm("Anne","a@example.com","Help","<script>alert(1)</script>"))
        assertFalse(validContactForm("Anne","a@example.com\r\nBcc: victim@example.com","Help",base))
        assertFalse(validContactForm("Anne","a@localhost","Help",base))
        assertFalse(validContactForm("Anne","a@example.com","Help","a".repeat(4001)))
        assertFalse(validContactForm("Anne","a@example.com","Help",base+'\u0000'))
        assertFalse(validContactForm("Anne","a@example.com","Help",base+'\u202E'))
        assertEquals(base,contactMessageInput(base,"<img src=x onerror=alert(1)>"))
        assertEquals("Anne",contactNameInput("Anne","Anne\nBcc"))
    }
}
