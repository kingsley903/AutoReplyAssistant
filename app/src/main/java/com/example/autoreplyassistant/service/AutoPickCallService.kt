package com.example.autoreplyassistant.service

import android.telecom.Call
import android.telecom.InCallService
import com.example.autoreplyassistant.data.ActivityLogStore
import com.example.autoreplyassistant.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AutoPickCallService : InCallService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (call.state != Call.STATE_RINGING) return

        serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                if (settings.autoPickCallsEnabled && call.state == Call.STATE_RINGING) {
                    call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
                    ActivityLogStore.add("CALL ANSWERED", "Automatically answered an incoming phone call")
                }
            } catch (error: Exception) {
                ActivityLogStore.add("ERROR", "Could not auto-answer call: ${error.message ?: "Unknown error"}")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
