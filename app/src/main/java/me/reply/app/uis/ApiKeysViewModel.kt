package me.reply.app.uis

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import me.reply.app.data.AddKeyResult
import me.reply.app.data.ApiKey
import me.reply.app.data.ApiKeyRepository
import me.reply.app.data.DeleteKeyResult
import javax.inject.Inject

@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository
) : ViewModel() {

    /** Live list of all stored keys — observed by the UI. */
    val keys: StateFlow<List<ApiKey>> = apiKeyRepository.keysFlow

    fun addKey(keyString: String, label: String): AddKeyResult =
        apiKeyRepository.addKey(keyString, label)

    fun deleteKey(id: String): DeleteKeyResult =
        apiKeyRepository.deleteKey(id)

    fun setActiveKey(id: String) =
        apiKeyRepository.setActiveKey(id)

    /** Reactive active key ID — UI collects this so it updates instantly. */
    val activeKeyId: StateFlow<String?> = apiKeyRepository.activeKeyIdFlow

    fun maskedKey(key: String): String =
        apiKeyRepository.maskedKey(key)
}
