package app.revive.tosave.mesh

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.util.*

/**
 * ViewModel for managing mesh chat functionality, state, and user interactions
 */
class MeshChatViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MeshChatViewModel"
        private const val CURRENT_USER_PREF = "current_mesh_user"
        private const val USERNAME_PREF = "mesh_username"
        private const val DISPLAY_NAME_PREF = "mesh_display_name"
    }

    // Core managers
    private val meshDatabase: MeshDatabase by lazy {
        MeshDatabaseProvider.getDatabase(application)
    }

    private lateinit var bluetoothMeshManager: BluetoothMeshManager
    private lateinit var routingManager: MeshRoutingManager
    private lateinit var encryptionManager: MeshEncryptionManager

    // Current user information
    private val _currentUser = MutableStateFlow<MeshUser?>(null)
    val currentUser: StateFlow<MeshUser?> = _currentUser.asStateFlow()

    // Network status
    private val _networkStatus = MutableStateFlow(MeshNetworkStatus())
    val networkStatus: StateFlow<MeshNetworkStatus> = _networkStatus.asStateFlow()

    // Discovered users
    private val _discoveredUsers = MutableStateFlow<List<MeshUser>>(emptyList())
    val discoveredUsers: StateFlow<List<MeshUser>> = _discoveredUsers.asStateFlow()

    // Active conversations
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    // Current conversation
    private val _currentConversation = MutableStateFlow<List<MeshMessage>>(emptyList())
    val currentConversation: StateFlow<List<MeshMessage>> = _currentConversation.asStateFlow()

    // UI events
    private val _events = MutableSharedFlow<MeshChatEvent>()
    val events: SharedFlow<MeshChatEvent> = _events.asSharedFlow()

    // UI state
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Initialization state
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    data class UiState(
        val isNetworkActive: Boolean = false,
        val connectionCount: Int = 0,
        val messagesSent: Int = 0,
        val messagesReceived: Int = 0,
        val lastActivity: Long = 0L,
        val showUserDiscovery: Boolean = false,
        val showNetworkDiagnostics: Boolean = false,
        val isKeyExchangeInProgress: Boolean = false,
        val encryptionStatus: MeshEncryptionManager.EncryptionStatus = MeshEncryptionManager.EncryptionStatus.NO_ENCRYPTION
    )

    data class ConversationSummary(
        val user: MeshUser,
        val lastMessage: MeshMessage?,
        val unreadCount: Int = 0,
        val isOnline: Boolean = false,
        val encryptionStatus: MeshEncryptionManager.EncryptionStatus = MeshEncryptionManager.EncryptionStatus.NO_ENCRYPTION
    )

    sealed class MeshChatEvent {
        object NetworkStarted : MeshChatEvent()
        object NetworkStopped : MeshChatEvent()
        data class UserDiscovered(val user: MeshUser) : MeshChatEvent()
        data class UserLost(val user: MeshUser) : MeshChatEvent()
        data class MessageReceived(val message: MeshMessage) : MeshChatEvent()
        data class MessageSent(val message: MeshMessage) : MeshChatEvent()
        data class Error(val message: String, val exception: Throwable? = null) : MeshChatEvent()
        data class EncryptionEstablished(val userId: String) : MeshChatEvent()
        data class SOSReceived(val message: MeshMessage, val sender: MeshUser) : MeshChatEvent()
    }

    /**
     * Initialize the mesh chat system
     */
    suspend fun initialize(username: String? = null, displayName: String? = null) {
        if (_isInitialized.value) return
        
        Log.d(TAG, "Initializing mesh chat system")
        
        try {
            // Create or load current user
            val user = createOrLoadCurrentUser(username, displayName)
            _currentUser.value = user
            
            // Initialize managers
            bluetoothMeshManager = BluetoothMeshManager(getApplication(), meshDatabase)
            routingManager = MeshRoutingManager(meshDatabase, user.id)
            encryptionManager = MeshEncryptionManager(meshDatabase, user.id)
            
            // Initialize core systems
            encryptionManager.initialize()
            routingManager.initialize()
            bluetoothMeshManager.initialize(user)
            
            // Set up data flows
            setupDataFlows()
            
            // Load existing data
            loadConversations()
            loadDiscoveredUsers()
            
            _isInitialized.value = true
            _events.emit(MeshChatEvent.NetworkStarted)
            
            Log.d(TAG, "Mesh chat system initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize mesh chat system", e)
            _events.emit(MeshChatEvent.Error("Failed to initialize mesh chat: ${e.message}", e))
            throw e
        }
    }

    /**
     * Send a message to a specific user
     */
    suspend fun sendMessage(content: String, receiverId: String): Boolean {
        val user = _currentUser.value ?: return false
        
        Log.d(TAG, "Sending message from ${user.username} to $receiverId: $content")
        
        try {
            val message = MeshMessage(
                senderId = user.id,
                receiverId = receiverId,
                content = content,
                messageType = MessageType.TEXT
            )
            
            val success = bluetoothMeshManager.sendMessage(message)
            
            if (success) {
                _events.emit(MeshChatEvent.MessageSent(message))
                loadConversationMessages(receiverId)
            }
            
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            _events.emit(MeshChatEvent.Error("Failed to send message: ${e.message}", e))
            return false
        }
    }

    private suspend fun createOrLoadCurrentUser(username: String?, displayName: String?): MeshUser {
        val prefs = getApplication<Application>().getSharedPreferences("mesh_chat", Context.MODE_PRIVATE)
        
        val existingUserId = prefs.getString(CURRENT_USER_PREF, null)
        if (existingUserId != null) {
            meshDatabase.userDao().getUserById(existingUserId)?.let { existingUser ->
                return existingUser
            }
        }
        
        val finalUsername = username ?: prefs.getString(USERNAME_PREF, null) ?: generateUsername()
        val finalDisplayName = displayName ?: prefs.getString(DISPLAY_NAME_PREF, null) ?: finalUsername
        
        val user = MeshUser(
            username = finalUsername,
            displayName = finalDisplayName
        )
        
        meshDatabase.userDao().insertUser(user)
        
        prefs.edit()
            .putString(CURRENT_USER_PREF, user.id)
            .putString(USERNAME_PREF, finalUsername)
            .putString(DISPLAY_NAME_PREF, finalDisplayName)
            .apply()
            
        return user
    }

    private fun generateUsername(): String {
        return try {
            val deviceId = android.provider.Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            "user_${deviceId?.take(8) ?: UUID.randomUUID().toString().take(8)}"
        } catch (e: Exception) {
            "user_${UUID.randomUUID().toString().take(8)}"
        }
    }

    private fun setupDataFlows() {
        // Monitor network status
        viewModelScope.launch {
            if (::bluetoothMeshManager.isInitialized) {
                bluetoothMeshManager.networkStatus.collect { status ->
                    _networkStatus.value = status
                    _uiState.value = _uiState.value.copy(
                        isNetworkActive = status.isActive,
                        connectionCount = status.connectedPeers,
                        messagesSent = status.messagesSent,
                        messagesReceived = status.messagesReceived,
                        lastActivity = status.lastActivity
                    )
                }
            }
        }

        // Monitor discovered users
        viewModelScope.launch {
            if (::bluetoothMeshManager.isInitialized) {
                bluetoothMeshManager.discoveredUsers.collect { users ->
                    _discoveredUsers.value = users
                }
            }
        }

        // Monitor incoming messages
        viewModelScope.launch {
            if (::bluetoothMeshManager.isInitialized) {
                bluetoothMeshManager.messageEvents.collect { message ->
                    handleIncomingMessage(message)
                }
            }
        }
    }

    private suspend fun handleIncomingMessage(message: MeshMessage) {
        try {
            val sender = meshDatabase.userDao().getUserById(message.senderId)
            
            when (message.messageType) {
                MessageType.TEXT -> {
                    _events.emit(MeshChatEvent.MessageReceived(message))
                    loadConversations()
                }
                MessageType.SOS_EMERGENCY -> {
                    if (sender != null) {
                        _events.emit(MeshChatEvent.SOSReceived(message, sender))
                    }
                }
                else -> {
                    Log.d(TAG, "Received ${message.messageType} message from ${message.senderId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message", e)
        }
    }

    private suspend fun loadConversations() {
        try {
            val currentUserId = _currentUser.value?.id ?: return
            val contactIds = meshDatabase.messageDao().getConversationContacts(currentUserId)
            
            _conversations.value = contactIds.mapNotNull { contactId ->
                val user = meshDatabase.userDao().getUserById(contactId)
                val messages = meshDatabase.messageDao().getConversation(currentUserId, contactId)
                val lastMessage = messages.lastOrNull()
                val unreadCount = 0 // Simplified - could be enhanced later
                
                if (user != null) {
                    ConversationSummary(
                        user = user,
                        lastMessage = lastMessage,
                        unreadCount = unreadCount,
                        isOnline = _discoveredUsers.value.any { it.id == contactId }
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversations", e)
        }
    }

    private suspend fun loadDiscoveredUsers() {
        try {
            val users = meshDatabase.userDao().getAllUsers()
            _discoveredUsers.value = users.filter { it.id != _currentUser.value?.id }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load discovered users", e)
        }
    }

    suspend fun loadConversationMessages(otherUserId: String) {
        try {
            val currentUserId = _currentUser.value?.id ?: return
            val messages = meshDatabase.messageDao().getConversation(currentUserId, otherUserId)
            _currentConversation.value = messages.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation messages", e)
        }
    }

    /**
     * UI Methods - simplified implementations for basic functionality
     */
    fun selectUser(user: MeshUser) {
        viewModelScope.launch {
            loadConversationMessages(user.id)
        }
    }

    fun addTestUser() {
        viewModelScope.launch {
            try {
                val testUser = MeshUser(
                    username = "test_user_${System.currentTimeMillis() % 1000}",
                    displayName = "Test User"
                )
                meshDatabase.userDao().insertUser(testUser)
                loadDiscoveredUsers()
                _events.emit(MeshChatEvent.UserDiscovered(testUser))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add test user", e)
            }
        }
    }

    fun startUserDiscovery() {
        viewModelScope.launch {
            try {
                if (::bluetoothMeshManager.isInitialized) {
                    bluetoothMeshManager.triggerDiscovery()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start user discovery", e)
                _events.emit(MeshChatEvent.Error("Failed to start discovery: ${e.message}", e))
            }
        }
    }

    fun stopUserDiscovery() {
        viewModelScope.launch {
            try {
                // BluetoothMeshManager automatically manages discovery cycles
                Log.d(TAG, "Discovery stop requested (handled automatically)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop user discovery", e)
            }
        }
    }

    fun getNetworkStats(): MeshRoutingManager.NetworkStats? {
        return if (::routingManager.isInitialized) {
            routingManager.getNetworkStats()
        } else {
            null
        }
    }

    suspend fun sendSOSMessage(location: String? = null): Boolean {
        val user = _currentUser.value ?: return false
        
        return try {
            val sosMessage = MeshMessage(
                senderId = user.id,
                receiverId = "", // Broadcast to all
                content = "SOS Emergency${location?.let { " at $it" } ?: ""}",
                messageType = MessageType.SOS_EMERGENCY
            )
            
            val success = if (::bluetoothMeshManager.isInitialized) {
                bluetoothMeshManager.sendMessage(sosMessage)
            } else false
            
            if (success) {
                _events.emit(MeshChatEvent.MessageSent(sosMessage))
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SOS message", e)
            _events.emit(MeshChatEvent.Error("Failed to send SOS: ${e.message}", e))
            false
        }
    }

    /**
     * Shutdown mesh chat system
     */
    override fun onCleared() {
        super.onCleared()
        
        Log.d(TAG, "Shutting down mesh chat system")
        
        if (::bluetoothMeshManager.isInitialized) {
            bluetoothMeshManager.shutdown()
        }
        
        if (::routingManager.isInitialized) {
            routingManager.shutdown()
        }
        
        if (::encryptionManager.isInitialized) {
            encryptionManager.shutdown()
        }
    }
}