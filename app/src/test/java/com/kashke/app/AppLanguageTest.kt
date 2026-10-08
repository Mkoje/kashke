package com.kashke.app

import org.junit.Assert.*
import org.junit.Test

class AppLanguageTest {
    @Test fun languageChangesLabelsAndPreservesMemberNamesAndAmounts() {
        try {
            AppLanguage.code = "en"
            assertEquals("Settings", tr("Settings"))
            AppLanguage.code = "sw"
            assertEquals("Mipangilio", tr("Settings"))
            assertEquals("Umeingia kama Jane Doe", tr("Signed in as Jane Doe"))
            assertEquals("Jane Doe anahitaji KSh 1,250", tr("Jane Doe needs KSh 1,250"))
            assertTrue(tr("Based on 4 settled or due agreements").startsWith("Kulingana"))
            assertEquals("Jumla ya malipo: KSh 1,250", tr("Total repayment: KSh 1,250"))
            assertEquals("Muda wa kulipa: 30 siku", tr("Repayment period: 30 days"))
            assertTrue(legalContent("Privacy Policy").startsWith("Sera ya Faragha"))
            assertTrue(legalContent("FAQ").contains("Ninatumiaje"))
            AppLanguage.code = "en"
            assertTrue(legalContent("Privacy Policy").startsWith("Kash.ke Privacy Policy"))
            assertEquals("Settings", tr("Settings"))
        } finally { AppLanguage.code = "en" }
    }
}
