package me.reply.app.uis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import me.reply.app.data.UserSettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettings: UserSettingsRepository
) : ViewModel() {


    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            userSettings.saveApiKey(apiKey)
        }
    }
}
