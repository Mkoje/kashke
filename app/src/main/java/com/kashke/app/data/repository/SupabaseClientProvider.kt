package com.kashke.app.data.repository

import android.content.Context
import com.kashke.app.BuildConfig
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

object SupabaseClientProvider {
    @Volatile private var applicationContext: Context? = null

    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client by lazy { create() }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    private fun create() = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Auth) {
            sessionManager = EncryptedSessionManager(
                checkNotNull(applicationContext) { "Initialize SupabaseClientProvider from the Application first." }
            )
            autoLoadFromStorage = true
            autoSaveToStorage = true
            alwaysAutoRefresh = true
        }
        install(Postgrest)
        install(Realtime)
    }
}
