package app.revive.tosave

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()
    
    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()
    
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    
    fun updateMessageText(text: String) {
        _messageText.value = text
    }
    
    fun addMessage(message: String) {
        _messages.value = _messages.value + message
    }
    
    fun sendMessage(nearbyManager: NearbyManager) {
        val message = _messageText.value.trim()
        if (message.isNotEmpty()) {
            val formattedMessage = "Me: $message"
            addMessage(formattedMessage)
            nearbyManager.sendMessage(message)
            clearMessageText()
        }
    }
    
    fun receiveMessage(message: String) {
        val formattedMessage = "Other: $message"
        addMessage(formattedMessage)
    }
    
    fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
    }
    
    private fun clearMessageText() {
        _messageText.value = ""
    }
}