package com.kashke.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeErrorsTest {
    @Test fun disabledPhoneSignupUsesServerConfigurationMessage() {
        for (reason in listOf("Phone signups are disabled", "phone_provider_disabled", "Phone provider is disabled")) {
            val failure = AuthFlowStageException("AUTH SIGNUP", RuntimeException(reason))
            assertEquals("Phone signup is not enabled on the server yet.",
                failure.toAuthMessage("Sign up failed."))
        }
    }

    @Test fun supportOfferFailureUsesUnderlyingServerReason() {
        val failure = AuthFlowStageException("RPC create_loan_offer",
            RuntimeException("Offer exceeds your maximum lending amount"))
        assertEquals("This offer exceeds your configured maximum lending amount. Choose a lower amount.",
            failure.toSupportOfferMessage())
        assertEquals("This request has expired or is no longer available. Refresh your requests.",
            RuntimeException("Request is no longer available").toSupportOfferMessage())
    }

    @Test fun supportOfferFailureDoesNotExposeRemoteDetailsOrAssumeInsufficientBalance() {
        val result = RuntimeException("Unexpected database error Authorization: Bearer secret-token").toSupportOfferMessage()
        assertEquals("The support offer could not be confirmed. Refresh your offers before trying again.", result)
        assertFalse(result.contains("secret-token"))
        assertFalse(result.contains("balance"))
        assertEquals("The server's support-offer API needs updating. Apply the latest Kash.ke database migrations.",
            RuntimeException("PGRST202 schema cache").toSupportOfferMessage())
    }

    @Test fun unconfirmedEmailGetsActionableMessage() {
        assertEquals(
            "Check your email to confirm your account, then sign in.",
            RuntimeException("Email not confirmed").toAuthMessage("Sign in failed.")
        )
    }

    @Test fun developmentAuthErrorsHideInternalIdentifier() {
        val email = internalAuthEmail("0715501556")
        for (reason in listOf("invalid login credentials", "invalid_credentials")) {
            assertEquals("Incorrect email/phone or password.",
                AuthFlowStageException("AUTH SIGNIN", RuntimeException("$reason $email"))
                    .toAuthMessage("Sign in failed."))
        }
        assertEquals("An account already exists with this email address.",
            RuntimeException("User already registered $email").toAuthMessage("Signup failed."))
        for (reason in listOf("", "duplicate", "email not confirmed", "network")) {
            assertFalse(RuntimeException("$reason $email").toAuthMessage("Please try again.").contains(email))
        }
        assertFalse(safeDiagnosticMessage(RuntimeException(email)).contains(email))
    }

    @Test fun diagnosticDetailsRedactCredentialMaterial() {
        val exception = RuntimeException(
            "Authorization: Bearer access-token-secret apikey=api-secret refresh_token=refresh-secret password=pw-secret " +
                "eyJhbGci.signature.payload"
        )
        val result = safeDiagnosticMessage(exception)
        for (secret in listOf("access-token-secret", "api-secret", "refresh-secret", "pw-secret", "eyJhbGci")) {
            assertFalse(result.contains(secret))
        }
    }

    @Test fun remoteDiagnosticsNeverAppearInUserMessage() {
        val sensitive = "Authorization: Bearer access-token-secret apikey: api-secret refresh_token=refresh-secret"
        for (prefix in listOf("", "invalid login credentials", "schema cache", "failed to connect", "duplicate")) {
            val result = RuntimeException("$prefix $sensitive").toAuthMessage("Please try again.")
            for (secret in listOf("access-token-secret", "api-secret", "refresh-secret", "Authorization", "apikey")) {
                assertFalse(result.contains(secret))
            }
        }
        assertEquals("Please try again.", RuntimeException(sensitive).toAuthMessage("Please try again."))
    }
}
