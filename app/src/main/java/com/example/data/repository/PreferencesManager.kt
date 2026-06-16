package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("era_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_OWNER_NAME = "owner_name"
        private const val KEY_GUEST_MODE = "guest_mode"
        private const val KEY_PERMITTED_GUESTS = "permitted_guests"
        private const val KEY_IS_TRAINED = "is_voice_trained"
        private const val KEY_TRAINED_PHRASE = "trained_phrase"
        private const val KEY_VOICE_PITCH = "voice_pitch"
        private const val KEY_VOICE_ENERGY = "voice_energy"
        private const val KEY_HOTWORD_ACTIVE = "hotword_active"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
    }

    var customApiKey: String
        get() = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_API_KEY, value).apply()

    var ownerName: String
        get() = prefs.getString(KEY_OWNER_NAME, "ফয়সাল আহমেদ") ?: "ফয়সাল আহমেদ"
        set(value) = prefs.edit().putString(KEY_OWNER_NAME, value).apply()

    var isGuestModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUEST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_GUEST_MODE, value).apply()

    var permittedGuests: String
        get() = prefs.getString(KEY_PERMITTED_GUESTS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERMITTED_GUESTS, value).apply()

    var isVoiceTrained: Boolean
        get() = prefs.getBoolean(KEY_IS_TRAINED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_TRAINED, value).apply()

    var trainedPhrase: String
        get() = prefs.getString(KEY_TRAINED_PHRASE, "ইরা শুরু করো") ?: "ইরা শুরু করো"
        set(value) = prefs.edit().putString(KEY_TRAINED_PHRASE, value).apply()

    var voicePitchSignature: Float
        get() = prefs.getFloat(KEY_VOICE_PITCH, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_PITCH, value).apply()

    var voiceEnergySignature: Float
        get() = prefs.getFloat(KEY_VOICE_ENERGY, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_VOICE_ENERGY, value).apply()

    var isHotwordActive: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ACTIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_HOTWORD_ACTIVE, value).apply()

    fun permitGuest(name: String) {
        val current = permittedGuests.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (!current.contains(name)) {
            current.add(name)
            permittedGuests = current.joinToString(",")
        }
    }

    fun revokeGuest(name: String) {
        val current = permittedGuests.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        current.remove(name)
        permittedGuests = current.joinToString(",")
    }

    fun isGuestPermitted(name: String): Boolean {
        if (isGuestModeEnabled) return true
        val current = permittedGuests.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return current.any { it.equals(name, ignoreCase = true) }
    }
}
