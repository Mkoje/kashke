package com.kashke.app.data.repository

import com.kashke.app.BuildConfig
import io.github.jan.supabase.gotrue.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal fun normalizeDepositPhone(value: String): String? {
    return com.kashke.app.data.strictKenyanPhone(value)
}

data class MpesaDeposit(val id: String?, val status: String, val amount: Int?, val receipt: String?)

object MpesaDeposits {
    private val http = HttpClient(Android) {
        install(HttpTimeout) { requestTimeoutMillis = 60000; connectTimeoutMillis = 15000 }
    }
    suspend fun start(amount: Int, phone: String): MpesaDeposit {
        require(amount in 1..150000) { "Enter a whole amount between KSh 1 and 150,000." }
        val normalized = requireNotNull(normalizeDepositPhone(phone)) { "Enter a valid Kenyan phone number." }
        return call(buildJsonObject { put("action", "deposit"); put("amount", amount); put("phone", normalized) })
    }
    suspend fun latest(): MpesaDeposit = call(buildJsonObject { put("action", "status") })
    private suspend fun call(body: JsonObject): MpesaDeposit {
        val token = SupabaseClientProvider.client.auth.currentSessionOrNull()?.accessToken
            ?: error("Sign in to deposit.")
        try {
            val response = http.post("${BuildConfig.SUPABASE_URL}/functions/v1/mpesa-deposit") {
                header("Authorization", "Bearer $token")
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            if (response.status.value !in 200..299) error("Unable to confirm the request. Check deposit status before trying again.")
            return MpesaDeposit(json["id"]?.jsonPrimitive?.contentOrNull,
                json.getValue("status").jsonPrimitive.content,
                json["amount"]?.jsonPrimitive?.intOrNull,
                json["receipt"]?.jsonPrimitive?.contentOrNull)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error("Unable to confirm the request. Check deposit status before trying again.") }
    }
}
