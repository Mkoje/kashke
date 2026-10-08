package com.kashke.app

import com.kashke.app.data.repository.normalizeDepositPhone
import org.junit.Assert.*
import org.junit.Test

class MpesaDepositsTest {
    @Test fun normalizesKenyanNumbersWithoutGuessingExtraDigits() {
        assertEquals("254712203900", normalizeDepositPhone("0712203900"))
        assertEquals("254712203900", normalizeDepositPhone("+254712203900"))
        assertNull(normalizeDepositPhone("+254 712 203 900"))
        assertEquals("254112203900", normalizeDepositPhone("0112203900"))
        assertNull(normalizeDepositPhone("07122003900"))
        assertNull(normalizeDepositPhone("0712203900x"))
        assertNull(normalizeDepositPhone("+255712203900"))
    }
}
