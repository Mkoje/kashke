package com.kashke.app.data.repository

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.minimalSettings
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AuthProviderRequestsTest {
    @Test fun passwordChangeChecksCurrentCredentialsBeforeUpdatingPassword() = runBlocking {
        for (phone in listOf(false, true)) for (validCurrent in listOf(false, true)) {
            val requests = mutableListOf<Pair<String, JsonObject>>()
            var rejectLogin = false
            val user = """{"id":"00000000-0000-0000-0000-000000000001","aud":"authenticated","role":"authenticated","email":"${if (phone) "" else "jane@example.com"}","phone":"${if (phone) "+254715501556" else ""}","app_metadata":{},"user_metadata":{},"created_at":"2026-10-05T00:00:00Z"}"""
            val engine = MockEngine { request ->
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                requests += request.url.encodedPath to Json.parseToJsonElement(body).jsonObject
                if (request.url.encodedPath == "/auth/v1/token" && rejectLogin) {
                    respond("""{"error":"invalid_grant","error_description":"Invalid login credentials"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json"))
                } else respond(if (request.url.encodedPath == "/auth/v1/user") user else """{"access_token":"test-access","refresh_token":"test-refresh","token_type":"bearer","expires_in":3600,"user":$user}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = createSupabaseClient("https://test.supabase.co", "public-test-key") {
                httpEngine = engine; install(Auth) { minimalSettings() }; install(Postgrest)
            }
            try {
                val repository = SupabaseRepository(client)
                repository.login(if (phone) "0715501556" else "jane@example.com", "Oldpass9!")
                requests.clear(); rejectLogin = !validCurrent
                val result = runCatching { repository.changePassword("Oldpass9!", "Newpass8!") }
                assertEquals(validCurrent, result.isSuccess)
                val updates = requests.filter { it.first == "/auth/v1/user" }
                assertEquals(if (validCurrent) 1 else 0, updates.size)
                if (validCurrent) {
                    assertEquals("Oldpass9!", requests.first().second.getValue("password").jsonPrimitive.content)
                    assertEquals("Newpass8!", updates.single().second.getValue("password").jsonPrimitive.content)
                    assertFalse(requests.any { it.first.contains("reauthenticate") || it.first.contains("recover") })
                }
            } finally { client.close() }
        }
    }
    @Test fun signupAndLoginSendRealEmailOrPhoneAndPreserveAuthUuid() = runBlocking {
        for (signup in listOf(true, false)) for (phone in listOf(true, false)) {
            val requests = mutableListOf<Pair<String, JsonObject>>()
            val identifier = if (phone) "0715501556" else " Jane@Example.com "
            val field = if (phone) "phone" else "email"
            val normalized = if (phone) "+254715501556" else "jane@example.com"
            val engine = MockEngine { request ->
                val body = when (val content = request.body) {
                    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
                    else -> error("Unexpected request body")
                }
                requests += request.url.encodedPath to Json.parseToJsonElement(body).jsonObject
                respond("""{"access_token":"test-access","refresh_token":"test-refresh","token_type":"bearer","expires_in":3600,"user":{"id":"00000000-0000-0000-0000-000000000001","aud":"authenticated","role":"authenticated","email":"${if (phone) "" else normalized}","phone":"${if (phone) normalized else ""}","app_metadata":{},"user_metadata":{},"created_at":"2026-10-05T00:00:00Z"}}""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val client = createSupabaseClient("https://test.supabase.co", "public-test-key") {
                httpEngine = engine
                install(Auth) { minimalSettings() }
                install(Postgrest)
            }
            try {
                val repository = SupabaseRepository(client)
                val identity = if (signup) repository.register(identifier, "Testpass9!") else repository.login(identifier, "test-password")
                assertNotNull(identity)
                assertEquals("00000000-0000-0000-0000-000000000001", identity!!.id)
                val (path, payload) = requests.single()
                assertEquals(if (signup) "/auth/v1/signup" else "/auth/v1/token", path)
                assertEquals(normalized, payload.getValue(field).jsonPrimitive.content)
                assertFalse(payload.containsKey(if (phone) "email" else "phone"))
                assertEquals(if (signup) "Testpass9!" else "test-password", payload.getValue("password").jsonPrimitive.content)
                if (signup) assertFalse(payload.toString().contains("@auth.kashke.local"))
            } finally { client.close() }
        }
    }
    @Test fun authDiagnosticsNeverCopyPasswordOrTokenBodies() {
        val error = RuntimeException("invalid_credentials password='long secret password' access_token=secret refresh_token=secret apikey=secret")
        assertEquals("invalid_credentials", authFailureCategory(error))
        assertFalse(authFailureCategory(error).contains("secret"))
    }
}
