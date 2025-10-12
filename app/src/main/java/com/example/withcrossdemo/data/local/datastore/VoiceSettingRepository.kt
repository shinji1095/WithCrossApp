package com.example.withcrossdemo.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.voiceDataStore by preferencesDataStore(name = "voice_prefs")

class VoiceSettingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_VOICE_SET = stringPreferencesKey("voice_set")
        const val DEFAULT_SET = "builtin"
    }

    val voiceSetFlow: Flow<String> =
        context.voiceDataStore.data.map { it[KEY_VOICE_SET] ?: DEFAULT_SET }

    suspend fun setVoiceSet(name: String) {
        context.voiceDataStore.edit { it[KEY_VOICE_SET] = name }
    }
}
