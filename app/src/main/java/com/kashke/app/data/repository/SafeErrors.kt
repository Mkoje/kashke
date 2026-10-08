package com.kashke.app.data.repository

import android.util.Log
import com.kashke.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AuthFlowStageException(val stage: String, cause: Throwable) :
    RuntimeException("$stage failed", cause)

internal suspend fun <T> runLoggedStage(stage: String, action: suspend () -> T): T = try {
    action()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    logStageFailure(stage, exception)
    throw AuthFlowStageException(stage, exception)
}

internal fun logStageFailure(stage: String, exception: Throwable) {
    val isAuth = stage.startsWith("AUTH ") || stage == "SESSION RESTORE" || stage == "PASSWORD RESET"
    val safeMessage = if (isAuth) authFailureCategory(exception) else safeDiagnosticMessage(exception, stage)
    val details = "stage=$stage exception=${exception.javaClass.simpleName} message=$safeMessage"
    if (BuildConfig.DEBUG && !isAuth) {
        Log.e("KashKeRuntime", "$details\n${safeDiagnosticStack(exception)}")
    } else {
        Log.e("KashKeRuntime", details)
    }
}

private fun safeDiagnosticStack(exception: Throwable): String =
    sanitizeDiagnosticText(android.util.Log.getStackTraceString(exception), 4000)

internal fun safeDiagnosticMessage(exception: Throwable, context: String = ""): String {
    val rawMessage = exception.message.orEmpty().ifBlank { "No exception message" }
    val contextText = if (context.isBlank()) "" else " context=$context"
    return sanitizeDiagnosticText("$rawMessage$contextText", 400)
}

internal fun safeRpcDiagnosticContext(functionName: String, params: JsonObject? = null, responseSummary: String? = null): String {
    val parameterSummary = params?.entries?.joinToString(" ") { (key, value) ->
        when (value) {
            is JsonArray -> "$key:array:${value.size}"
            is JsonObject -> "$key:object:${value.size}"
            is JsonPrimitive -> "$key:${value.content::class.simpleName}:${value.content.length}"
            else -> "$key:${value::class.simpleName}"
        }
    } ?: "params=none"
    val responseText = responseSummary?.let { " response=$it" } ?: ""
    return "rpc=$functionName $parameterSummary$responseText"
}

private fun sanitizeDiagnosticText(rawMessage: String, limit: Int): String = rawMessage
        .replace(Regex("(?i)[^\\s@\"']+@auth\\.kashke\\.local"), "[INTERNAL_AUTH_ID]")
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*"), "$1[REDACTED]")
        .replace(
            Regex("(?i)([\\\"']?(?:authorization|apikey|api_key|access_token|refresh_token|password)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+"),
            "$1[REDACTED]"
        )
        .replace(Regex("(?i)eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[REDACTED_TOKEN]")
        .replace(Regex("sb_(?:publishable|secret)_[A-Za-z0-9_-]+"), "[REDACTED_KEY]")
        .take(limit)

/** Remote exception text is inspected only for known categories, never returned. */
internal fun Throwable.toSupportOfferMessage(): String {
    val normalized = generateSequence(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
    return when {
        "insufficient available wallet balance" in normalized ->
            "Your available wallet balance has changed. Refresh and choose an amount within your available balance."
        "offer exceeds your maximum lending amount" in normalized ->
            "This offer exceeds your configured maximum lending amount. Choose a lower amount."
        "request is no longer available" in normalized ->
            "This request has expired or is no longer available. Refresh your requests."
        "connect with this borrower first" in normalized ->
            "You need an accepted connection with this borrower before sending an offer."
        "this request is private" in normalized ->
            "Only the designated supporter can offer on this personal request."
        "invalid offer amount" in normalized ->
            "The amount exceeds the request remaining, or this is your own request. Refresh your requests."
        "above-limit" in normalized || "above limit" in normalized ->
            "This personal request requires the designated supporter to approve the disclosed borrowing-limit excess."
        "interest exceeds" in normalized || "invalid offer terms" in normalized ->
            "The interest or repayment period is outside the allowed range. Refresh and check the offer terms."
        "schema cache" in normalized || "could not find the function" in normalized || "pgrst202" in normalized ->
            "The server's support-offer API needs updating. Apply the latest Kash.ke database migrations."
        "sign in first" in normalized || "jwt expired" in normalized || "28000" in normalized ->
            "Your session has expired. Sign in again before sending an offer."
        "network" in normalized || "unable to resolve host" in normalized || "failed to connect" in normalized || "timeout" in normalized ->
            "The server response could not be confirmed. Check your connection and refresh your offers before trying again."
        else -> "The support offer could not be confirmed. Refresh your offers before trying again."
    }
}

/** Remote exception text is inspected only for known categories, never returned. */
internal fun Throwable.toAuthMessage(fallback: String): String {
    val normalized = generateSequence(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
        .lowercase(java.util.Locale.ROOT)
    return when {
        "enter a valid email address" in normalized -> "Enter a valid email address."
        "enter a valid kenyan mobile" in normalized -> "Enter a valid Kenyan mobile number, for example 712345678."
        "password must be at least 8" in normalized -> "Password must be at least 8 characters."
        "phone_provider_disabled" in normalized || "phone provider is disabled" in normalized ||
            "phone signups are disabled" in normalized -> "Phone signup is not enabled on the server yet."
        "database error saving new user" in normalized -> "This phone could not be registered. Try signing in if you already have an account."
        "invalid login credentials" in normalized || "invalid_credentials" in normalized -> "Incorrect email/phone or password."
        "phone not confirmed" in normalized -> "Phone confirmation is required. Contact support to finish account setup."
        "email not confirmed" in normalized || "did not create a signed-in session" in normalized ->
            "Check your email to confirm your account, then sign in."
        "username is already in use" in normalized -> "That username is already in use."
        "already registered" in normalized || "already exists" in normalized || "duplicate" in normalized ||
            "user_already_exists" in normalized || "phone_exists" in normalized || "email_exists" in normalized ->
            if (generateSequence(this) { it.cause }.filterIsInstance<AuthFlowStageException>().any { it.stage.endsWith("PHONE") })
                "An account already exists with this phone number." else "An account already exists with this email address."
        "schema cache" in normalized || "public_profile_directory" in normalized || "ensure_current_profile" in normalized ->
            "We couldn't load your account. Please try again."
        "network" in normalized || "unable to resolve host" in normalized || "failed to connect" in normalized ->
            "Check your internet connection and try again."
        else -> fallback
    }
}

internal fun Throwable.toAgreementMessage(): String {
    val message = generateSequence(this) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
    return when {
        "valid kenyan mobile" in message -> "Enter a valid Kenyan mobile number."
        "profiles_unique_mobile" in message -> "That phone number is already in use."
        "phone changes require" in message -> "Your phone number is already set and cannot be changed here."
        "grace period unavailable" in message -> "Grace is available once, from 3 days before until 3 days after the original deadline, for 1–30 extra days. Refresh the agreement."
        "offer terms changed" in message -> "The lender changed this offer. Refresh and review the updated terms before accepting."
        "offer unavailable" in message || "offer is no longer available" in message -> "This offer has changed or closed. Refresh your offers."
        "invalid offer terms" in message -> "Use a positive amount within the remaining request, 0–7% interest (up to 15% for approved above-limit requests), and 7 days up to the requested repayment period."
        "insufficient available wallet balance" in message -> "Your wallet cannot cover the updated offer. Choose a lower amount."
        "maximum lending amount exceeded" in message -> "This offer exceeds your maximum lending amount."
        "duplicate" in message || "username" in message && "unique" in message -> "That username is already in use. Choose another."
        "complete" in message && "profile" in message || "complete name" in message || "complete full name" in message -> "Complete your full name, username, email and phone number in Profile."
        else -> "We couldn't complete this action. Refresh your data and check the details before trying again."
    }
}

// Never serialize Auth exceptions: SDK errors may embed entire request bodies.
internal fun authFailureCategory(exception: Throwable): String {
    val text = generateSequence(exception) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        "invalid_credentials" in text || "invalid login credentials" in text -> "invalid_credentials"
        "already registered" in text || "already exists" in text || "email_exists" in text || "phone_exists" in text -> "account_exists"
        "not confirmed" in text -> "confirmation_required"
        "provider" in text && "disabled" in text -> "provider_disabled"
        "network" in text || "timeout" in text || "failed to connect" in text -> "connection_failure"
        "database error saving new user" in text -> "auth_provisioning_failure"
        else -> "authentication_failure"
    }
}
