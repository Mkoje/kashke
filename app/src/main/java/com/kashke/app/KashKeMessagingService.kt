package com.kashke.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kashke.app.data.Notification
import com.kashke.app.data.NotificationType
import com.kashke.app.data.repository.SupabaseClientProvider
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PushRegistration {
    private var registeredDevice: Pair<String, String>? = null
    private val tokenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun tokenChanged(context: Context, token: String) {
        val userId = context.getSharedPreferences("push_account", Context.MODE_PRIVATE).getString("user_id", null) ?: return
        tokenScope.launch {
            SupabaseClientProvider.client.auth.awaitInitialization()
            if (context.getSharedPreferences("push_account", Context.MODE_PRIVATE).getString("user_id", null) == userId)
                save(token, userId)
        }
    }
    fun register(context: Context, scope: CoroutineScope, userId: String) {
        context.getSharedPreferences("push_account", Context.MODE_PRIVATE).edit().putString("user_id", userId).apply()
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            scope.launch { save(token, userId) }
        }
    }

    fun clear(context: Context) {
        registeredDevice = null
        context.getSharedPreferences("push_account", Context.MODE_PRIVATE).edit().remove("user_id").apply()
    }

    suspend fun save(token: String, userId: String) {
        val client = SupabaseClientProvider.client
        if (client.auth.currentUserOrNull()?.id != userId) return
        if (registeredDevice == (userId to token)) return
        try {
            client.postgrest.rpc("register_push_device", buildJsonObject { put("p_token", token) })
            if (client.auth.currentUserOrNull()?.id == userId) registeredDevice = userId to token
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            android.util.Log.w("KashKePush", "Device registration failed; will retry on refresh.")
        }
    }
}

class KashKeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRegistration.tokenChanged(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val account = getSharedPreferences("push_account", Context.MODE_PRIVATE).getString("user_id", null)
        if (account == null || data["user_id"] != account) return
        val id = data["id"] ?: return
        val type = runCatching { NotificationType.valueOf(data["type"].orEmpty()) }.getOrNull() ?: return
        SystemNotifications.deliver(this, account, listOf(Notification(
            id = id, userId = account, type = type, title = data["title"].orEmpty(),
            message = data["message"].orEmpty(), read = false, createdAt = data["created_at"].orEmpty()
        )))
    }
}
