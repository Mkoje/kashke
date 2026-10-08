package com.kashke.app.data.repository

import com.kashke.app.data.*
import kotlinx.serialization.json.*

internal object DatabaseRecords {
    private val json = Json { ignoreUnknownKeys = true }
    private inline fun <reified T> decode(row: JsonObject, extra: Map<String, JsonElement> = emptyMap()): T =
        json.decodeFromJsonElement(JsonObject(row + extra))

    fun request(row: JsonObject): LoanRequest = decode(row)
    fun connection(row: JsonObject): Connection = decode(row)
    fun notification(row: JsonObject): Notification = decode(row)
    fun event(row: JsonObject): TrustEvent = decode(row, mapOf("type" to row.getValue("event_type")))
    fun transaction(row: JsonObject): Transaction = decode(row, mapOf("interest" to (row["interest"] ?: JsonPrimitive(0))))
    fun offer(row: JsonObject, requests: List<LoanRequest>): LoanOffer = decode(row, mapOf(
        "interest_rate" to JsonPrimitive(row.number("interest_rate")),
        "requested_interest_rate" to (row["requested_interest_rate"]?.takeUnless { it is JsonNull }
            ?.let { JsonPrimitive(row.number("requested_interest_rate")) } ?: JsonNull),
        "direct_request" to JsonPrimitive(requests.any { it.id == row.text("loan_request_id") && it.directRequestTo != null })
    ))
    fun loan(row: JsonObject, requests: List<LoanRequest>): LoanAgreement = decode(row, mapOf(
        "interest_rate" to JsonPrimitive(row.number("interest_rate")),
        "original_request_amount" to JsonPrimitive(requests.firstOrNull { it.id == row.text("loan_request_id") }?.requestedAmount ?: row.number("principal"))
    ))
    fun user(row: JsonObject, email: String, walletBalance: Int): User = decode(row, mapOf(
        "username" to JsonPrimitive("@" + row.text("username").removePrefix("@")),
        "email" to JsonPrimitive(email),
        "member_since" to JsonPrimitive(row.text("created_at").substringBefore('T')),
        "wallet_balance" to JsonPrimitive(walletBalance),
        "borrowing_limit" to (row["borrowing_limit"] ?: JsonPrimitive(0)),
        "available_limit" to (row["available_limit"] ?: JsonPrimitive(0))
    ))
}

internal fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.number(key: String): Int {
    // Reject overflow/fractional values rather than rounding money or interest silently.
    val value = getValue(key).jsonPrimitive.content.toBigDecimal().toBigIntegerExact()
    if (value < java.math.BigInteger.valueOf(Int.MIN_VALUE.toLong()) ||
        value > java.math.BigInteger.valueOf(Int.MAX_VALUE.toLong())
    ) {
        throw ArithmeticException("Integer overflow")
    }
    return value.toInt()
}
