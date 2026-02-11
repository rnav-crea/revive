package app.revive.tosave.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multi-peer mesh networking using Google Nearby Connections API
 * Perfect for hackathon demos with automatic multi-device connectivity
 */
class NearbyMeshManager(
    private val context: Context,
    private val meshDatabase: MeshDatabase
) {
    companion object {
        private const val TAG = "NearbyMeshManager"
        private const val SERVICE_ID = "app.revive.tosave.mesh" // Unique service identifier
        private val STRATEGY = Strategy.P2P_STAR // Star topology for multi-peer
    }

    private val connectionsClient by lazy { 
        try {
            Nearby.getConnectionsClient(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Nearby Connections client", e)
            throw IllegalStateException("Google Play Services not available or Nearby Connections not supported", e)
        }
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Network state
    private val _networkStatus = MutableStateFlow(MeshNetworkStatus())
    val networkStatus: StateFlow<MeshNetworkStatus> = _networkStatus.asStateFlow()

    private val _discoveredUsers = MutableStateFlow<List<MeshUser>>(emptyList())
    val discoveredUsers: StateFlow<List<MeshUser>> = _discoveredUsers.asStateFlow()

    private val _messageEvents = MutableSharedFlow<MeshMessage>()
    val messageEvents: SharedFlow<MeshMessage> = _messageEvents.asSharedFlow()

    // Connection management
    private val connectedEndpoints = ConcurrentHashMap<String, EndpointInfo>()
    private val discoveredEndpoints = ConcurrentHashMap<String, MeshUser>()
    private var currentUser: MeshUser? = null
    private var isAdvertising = false
    private var isDiscovering = false

    data class EndpointInfo(
        val endpointId: String,
        val user: MeshUser,
        val connectionTime: Long = System.currentTimeMillis()
    )

    /**
     * Initialize the mesh network using Nearby Connections
     */
    suspend fun initialize(user: MeshUser) {
        Log.d(TAG, "Initializing Nearby Connections mesh for user: ${user.username}")
        
        currentUser = user
        
        try {
            // Start both advertising and discovering for multi-peer connectivity
            startAdvertising()
            startDiscovering()
            
            _networkStatus.value = _networkStatus.value.copy(
                isActive = true,
                lastActivity = System.currentTimeMillis()
            )
            
            Log.d(TAG, "Nearby Connections mesh initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize mesh network", e)
            throw e
        }
    }

    /**
     * Start advertising this device for others to discover
     */
    private fun startAdvertising() {
        val user = currentUser ?: return
        
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        Log.d(TAG, "Starting advertising as: ${user.username}")
        
        connectionsClient.startAdvertising(
            user.username, // Advertised name
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            isAdvertising = true
            Log.d(TAG, "Advertising started successfully")
            updateNetworkStatus()
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to start advertising", exception)
            isAdvertising = false
        }
    }

    /**
     * Start discovering other devices
     */
    private fun startDiscovering() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        Log.d(TAG, "Starting device discovery")
        
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            isDiscovering = true
            Log.d(TAG, "Discovery started successfully")
            updateNetworkStatus()
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to start discovery", exception)
            isDiscovering = false
        }
    }

    /**
     * Callback for handling connection lifecycle events
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with $endpointId (${connectionInfo.endpointName})")
            
            // Automatically accept all connections for mesh networking
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnSuccessListener {
                    Log.d(TAG, "Accepted connection with $endpointId")
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to accept connection with $endpointId", exception)
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected successfully to $endpointId")
                    
                    // Add to connected endpoints
                    discoveredEndpoints[endpointId]?.let { user ->
                        connectedEndpoints[endpointId] = EndpointInfo(endpointId, user)
                        updateDiscoveredUsers()
                        updateNetworkStatus()
                        
                        // Send introduction message
                        sendIntroduction(endpointId)
                    }
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by $endpointId")
                }
                else -> {
                    Log.e(TAG, "Connection failed with $endpointId: ${result.status}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            
            connectedEndpoints.remove(endpointId)
            updateDiscoveredUsers()
            updateNetworkStatus()
        }
    }

    /**
     * Callback for discovering nearby endpoints
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered endpoint: $endpointId (${info.endpointName})")
            
            // Create user from discovered endpoint
            val user = MeshUser(
                id = endpointId,
                username = info.endpointName,
                displayName = info.endpointName,
                lastSeen = System.currentTimeMillis()
            )
            
            discoveredEndpoints[endpointId] = user
            updateDiscoveredUsers()
            
            // Automatically connect to discovered endpoints for mesh networking
            connectToEndpoint(endpointId, user)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Lost endpoint: $endpointId")
            
            discoveredEndpoints.remove(endpointId)
            connectedEndpoints.remove(endpointId)
            updateDiscoveredUsers()
            updateNetworkStatus()
        }
    }

    /**
     * Connect to a discovered endpoint
     */
    private fun connectToEndpoint(endpointId: String, user: MeshUser) {
        Log.d(TAG, "Connecting to endpoint: $endpointId (${user.username})")
        
        connectionsClient.requestConnection(
            currentUser?.username ?: "Unknown",
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d(TAG, "Connection request sent to $endpointId")
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to request connection to $endpointId", exception)
        }
    }

    /**
     * Callback for handling received payloads (messages)
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val messageJson = String(payload.asBytes()!!, StandardCharsets.UTF_8)
                Log.d(TAG, "Received payload from $endpointId: $messageJson")
                
                try {
                    val message = Json.decodeFromString<MeshMessage>(messageJson)
                    
                    // Handle in a coroutine since handleReceivedMessage is suspend
                    coroutineScope.launch {
                        handleReceivedMessage(message, endpointId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse received message", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                Log.d(TAG, "Payload sent successfully to $endpointId")
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.e(TAG, "Failed to send payload to $endpointId")
            }
        }
    }

    /**
     * Handle received messages and relay to other endpoints
     */
    private suspend fun handleReceivedMessage(message: MeshMessage, fromEndpointId: String) {
        Log.d(TAG, "Handling message: ${message.content} from $fromEndpointId")
        
        // Store message in database
        meshDatabase.messageDao().insertMessage(message)
        
        // Emit message event
        _messageEvents.emit(message)
        
        // Relay message to all other connected endpoints (mesh behavior)
        relayMessageToOthers(message, fromEndpointId)
        
        updateNetworkStatus()
    }

    /**
     * Relay a message to all connected endpoints except the sender
     */
    private fun relayMessageToOthers(message: MeshMessage, excludeEndpointId: String) {
        val messageJson = Json.encodeToString(message)
        val payload = Payload.fromBytes(messageJson.toByteArray(StandardCharsets.UTF_8))
        
        connectedEndpoints.values.forEach { endpoint ->
            if (endpoint.endpointId != excludeEndpointId) {
                Log.d(TAG, "Relaying message to ${endpoint.user.username}")
                
                connectionsClient.sendPayload(endpoint.endpointId, payload)
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Failed to relay message to ${endpoint.endpointId}", exception)
                    }
            }
        }
    }

    /**
     * Send a message through the mesh network
     */
    suspend fun sendMessage(message: MeshMessage): Boolean {
        Log.d(TAG, "Sending mesh message: ${message.content}")
        
        return try {
            // Store message locally
            meshDatabase.messageDao().insertMessage(message)
            
            // Send to all connected endpoints
            val messageJson = Json.encodeToString(message)
            val payload = Payload.fromBytes(messageJson.toByteArray(StandardCharsets.UTF_8))
            
            var sentToAny = false
            connectedEndpoints.values.forEach { endpoint ->
                connectionsClient.sendPayload(endpoint.endpointId, payload)
                    .addOnSuccessListener {
                        Log.d(TAG, "Message sent to ${endpoint.user.username}")
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "Failed to send message to ${endpoint.endpointId}", exception)
                    }
                sentToAny = true
            }
            
            if (sentToAny) {
                updateNetworkStatus()
            } else {
                Log.w(TAG, "No connected endpoints to send message to")
            }
            
            sentToAny
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }

    /**
     * Send introduction message when connecting to a new endpoint
     */
    private fun sendIntroduction(endpointId: String) {
        val user = currentUser ?: return
        
        val introMessage = MeshMessage(
            id = UUID.randomUUID().toString(),
            content = "👋 ${user.username} joined the mesh",
            senderId = user.id,
            receiverId = "all",
            timestamp = System.currentTimeMillis(),
            messageType = MessageType.SYSTEM
        )
        
        val messageJson = Json.encodeToString(introMessage)
        val payload = Payload.fromBytes(messageJson.toByteArray(StandardCharsets.UTF_8))
        
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener {
                Log.d(TAG, "Introduction sent to $endpointId")
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to send introduction to $endpointId", exception)
            }
    }

    /**
     * Update discovered users list
     */
    private fun updateDiscoveredUsers() {
        val users = connectedEndpoints.values.map { it.user }
        _discoveredUsers.value = users
    }

    /**
     * Update network status
     */
    private fun updateNetworkStatus() {
        _networkStatus.value = _networkStatus.value.copy(
            connectedPeers = connectedEndpoints.size,
            isActive = isAdvertising || isDiscovering,
            lastActivity = System.currentTimeMillis()
        )
    }

    /**
     * Get current user ID
     */
    fun getCurrentUserId(): String {
        return currentUser?.id ?: "unknown"
    }

    /**
     * Trigger manual discovery - restart discovery process
     */
    suspend fun triggerDiscovery() {
        Log.d(TAG, "Triggering manual discovery")
        
        try {
            // Stop and restart discovery for fresh scan
            connectionsClient.stopDiscovery()
            delay(1000) // Brief pause
            startDiscovering()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger discovery", e)
        }
    }

    /**
     * Shutdown mesh network
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down Nearby Connections mesh")
        
        try {
            // Stop advertising and discovery
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            
            // Disconnect from all endpoints
            connectedEndpoints.keys.forEach { endpointId ->
                connectionsClient.disconnectFromEndpoint(endpointId)
            }
            
            // Clear state
            connectedEndpoints.clear()
            discoveredEndpoints.clear()
            isAdvertising = false
            isDiscovering = false
            
            // Cancel coroutines
            coroutineScope.cancel("Mesh network shutdown")
            
            // Reset network status
            _networkStatus.value = MeshNetworkStatus()
            _discoveredUsers.value = emptyList()
            
            Log.d(TAG, "Nearby Connections mesh shutdown completed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during mesh shutdown", e)
        }
    }
}