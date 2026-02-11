package app.revive.tosave

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*

class NearbyManager(
    private val context: Context,
    private val viewModel: ChatViewModel
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = "app.revive.tosave.chat"
    private var connectedEndpointId: String? = null
    private var isDiscovering = false
    private var isAdvertising = false
    
    // Coroutine scope for timeout management
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var discoveryTimeoutJob: Job? = null
    
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with $endpointId")
            viewModel.updateConnectionStatus("Connecting...")
            // Auto-accept connections immediately for faster pairing
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected to $endpointId")
                    connectedEndpointId = endpointId
                    viewModel.updateConnectionStatus("Connected")
                    // Stop discovery and advertising immediately after successful connection
                    stopDiscovery()
                    stopAdvertising()
                    viewModel.addMessage("✅ Connected to device")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d(TAG, "Connection rejected")
                    viewModel.updateConnectionStatus("Connection rejected")
                    // Continue discovery if connection was rejected
                    if (!isDiscovering) {
                        startDiscovery()
                    }
                }
                else -> {
                    Log.d(TAG, "Connection failed: ${result.status}")
                    viewModel.updateConnectionStatus("Connection failed")
                    // Retry discovery after failed connection
                    if (!isDiscovering) {
                        startDiscovery()
                    }
                }
            }
        }
        
        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            connectedEndpointId = null
            viewModel.updateConnectionStatus("Disconnected")
            viewModel.addMessage("❌ Device disconnected")
        }
    }
    
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val message = String(payload.asBytes()!!, Charsets.UTF_8)
                    Log.d(TAG, "Received message: $message")
                    viewModel.receiveMessage(message)
                }
            }
        }
        
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Handle payload transfer updates if needed
        }
    }
    
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: $endpointId, attempting immediate connection")
            viewModel.updateConnectionStatus("Device found, connecting...")
            
            // Immediately attempt connection when device is found
            connectionsClient.requestConnection(
                android.os.Build.MODEL, // Use device model as identifier
                endpointId,
                connectionLifecycleCallback
            ).addOnSuccessListener {
                Log.d(TAG, "Connection request sent to $endpointId")
            }.addOnFailureListener { exception ->
                Log.e(TAG, "Failed to request connection to $endpointId", exception)
                viewModel.updateConnectionStatus("Connection request failed")
            }
        }
        
        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            if (connectedEndpointId == endpointId) {
                connectedEndpointId = null
                viewModel.updateConnectionStatus("Connection lost")
            }
        }
    }
    
    fun startAdvertising() {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }
        
        // Optimized advertising options for faster discovery
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .setLowPower(false) // Use full power for faster discovery
            .build()
            
        connectionsClient.startAdvertising(
            android.os.Build.MODEL, // Use device model as advertised name
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started")
            isAdvertising = true
            viewModel.updateConnectionStatus("Advertising...")
            viewModel.addMessage("📡 Started advertising for connections")
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Advertising failed", exception)
            isAdvertising = false
            viewModel.updateConnectionStatus("Advertising failed")
            viewModel.addMessage("❌ Failed to start advertising")
        }
    }
    
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Already discovering")
            return
        }
        
        // Optimized discovery options for faster connection
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .setLowPower(false) // Use full power for faster discovery
            .build()
            
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started")
            isDiscovering = true
            viewModel.updateConnectionStatus("Discovering...")
            viewModel.addMessage("🔍 Searching for nearby devices...")
            
            // Set a timeout for discovery to restart if no devices found
            discoveryTimeoutJob?.cancel()
            discoveryTimeoutJob = scope.launch {
                delay(15000) // 15 second timeout
                if (isDiscovering && connectedEndpointId == null) {
                    Log.d(TAG, "Discovery timeout, restarting...")
                    stopDiscovery()
                    delay(1000) // Brief pause
                    startDiscovery()
                }
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Discovery failed", exception)
            isDiscovering = false
            viewModel.updateConnectionStatus("Discovery failed")
            viewModel.addMessage("❌ Failed to start discovery")
        }
    }
    
    fun stopAdvertising() {
        if (!isAdvertising) return
        
        connectionsClient.stopAdvertising()
        isAdvertising = false
        Log.d(TAG, "Advertising stopped")
    }
    
    fun stopDiscovery() {
        if (!isDiscovering) return
        
        connectionsClient.stopDiscovery()
        isDiscovering = false
        discoveryTimeoutJob?.cancel()
        Log.d(TAG, "Discovery stopped")
    }
    
    fun sendMessage(message: String) {
        connectedEndpointId?.let { endpointId ->
            val payload = Payload.fromBytes(message.toByteArray(Charsets.UTF_8))
            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    Log.d(TAG, "Message sent successfully")
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to send message", exception)
                    viewModel.updateConnectionStatus("Message send failed")
                }
        } ?: run {
            Log.w(TAG, "No connected endpoint to send message")
            viewModel.updateConnectionStatus("No connected device")
            viewModel.addMessage("❌ No device connected to send message")
        }
    }
    
    fun disconnect() {
        discoveryTimeoutJob?.cancel()
        
        connectedEndpointId?.let { endpointId ->
            connectionsClient.disconnectFromEndpoint(endpointId)
            connectedEndpointId = null
        }
        
        stopAdvertising()
        stopDiscovery()
        connectionsClient.stopAllEndpoints()
        
        viewModel.updateConnectionStatus("Disconnected")
        viewModel.addMessage("🔌 All connections closed")
    }
    
    // Check if currently connected
    fun isConnected(): Boolean = connectedEndpointId != null
    
    // Get current status
    fun getConnectionStatus(): String = when {
        connectedEndpointId != null -> "Connected"
        isAdvertising && isDiscovering -> "Advertising & Discovering"
        isAdvertising -> "Advertising"
        isDiscovering -> "Discovering"
        else -> "Disconnected"
    }
    
    companion object {
        private const val TAG = "NearbyManager"
    }
}