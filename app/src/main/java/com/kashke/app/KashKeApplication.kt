package com.kashke.app

import android.app.Application
import com.kashke.app.data.repository.SupabaseClientProvider

class KashKeApplication : Application() {
	override fun onCreate() {
		super.onCreate()
		SupabaseClientProvider.initialize(this)
		SystemNotifications.initialize(this)
	}
}
