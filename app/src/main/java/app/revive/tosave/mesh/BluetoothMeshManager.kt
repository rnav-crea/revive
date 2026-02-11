package app.revive.tosave.mesh

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Bluetooth Mesh networking for peer-to-peer communication
 */
class BluetoothMeshManager(
    private val context: Context,
    private val meshDatabase: MeshDatabase
) {
    companion object {
        private const val TAG = "BluetoothMeshManager"
        private val MESH_SERVICE_UUID = UUID.fromString("12345678-1234-5678-9ABC-DEF012345678")
        private val MESH_CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-8765-CBA9-FED021543876")
        
        // Optimized for real devices: longer periods for better battery life and discovery
        private const val SCAN_PERIOD = 30000L // 30 seconds - longer for better discovery
        private const val ADVERTISE_PERIOD = 60000L // 60 seconds - longer for stability
        private const val DISCOVERY_INTERVAL = 5000L // 5 seconds pause between scans
        private const val MAX_CONCURRENT_CONNECTIONS = 4 // Limit for stability
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    private val bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser

    private val _networkStatus = MutableStateFlow(MeshNetworkStatus())
    val networkStatus: StateFlow<MeshNetworkStatus> = _networkStatus.asStateFlow()

    private val _discoveredUsers = MutableStateFlow<List<MeshUser>>(emptyList())
    val discoveredUsers: StateFlow<List<MeshUser>> = _discoveredUsers.asStateFlow()

    private val _messageEvents = MutableSharedFlow<MeshMessage>()
    val messageEvents: SharedFlow<MeshMessage> = _messageEvents.asSharedFlow()

    private val connectedDevices = ConcurrentHashMap<String, BluetoothGatt>()
    private val pendingMessages = ConcurrentHashMap<String, MeshMessage>()
    
    /**
     * Check if all required Bluetooth permissions are granted for real device operation
     */
    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>().apply {
            // Basic Bluetooth permissions
            add(android.Manifest.permission.BLUETOOTH)
            add(android.Manifest.permission.BLUETOOTH_ADMIN)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            
            // Android 12+ permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_SCAN)
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if Bluetooth is properly enabled and hardware is available
     */
    private fun isBluetoothReady(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter not available")
            return false
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "Bluetooth LE not supported")
            return false
        }
        
        return true
    }
    private val routingTable = ConcurrentHashMap<String, MeshRoute>()

    private var isScanning = false
    private var isAdvertising = false
    private var gattServer: BluetoothGattServer? = null
    private var currentUser: MeshUser? = null

    // Callback references for proper cleanup
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    // Connection retry tracking
    private val connectionAttempts = ConcurrentHashMap<String, Int>()
    private val maxRetryAttempts = 3
    private val retryDelayMs = 2000L

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initialize the mesh network
     */
    suspend fun initialize(currentUser: MeshUser) {
        Log.d(TAG, "Initializing Bluetooth Mesh Manager for user: ${currentUser.username}")
        
        // Check permissions and Bluetooth state for real devices
        if (!hasRequiredPermissions()) {
            val error = "Required Bluetooth permissions not granted"
            Log.e(TAG, error)
            throw SecurityException(error)
        }
        
        if (!isBluetoothReady()) {
            val error = "Bluetooth is not ready or not supported"
            Log.e(TAG, error)
            throw IllegalStateException(error)
        }
        
        this.currentUser = currentUser
        
        try {
            setupGattServer()
            startScanning()
            startAdvertising(currentUser)
            startPeriodicDiscovery()
            
            _networkStatus.value = _networkStatus.value.copy(
                isActive = true,
                lastActivity = System.currentTimeMillis()
            )
            
            Log.d(TAG, "Mesh network initialized successfully for real device deployment")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize mesh network", e)
            throw e
        }
    }

    /**
     * Trigger manual user discovery
     */
    fun triggerDiscovery() {
        Log.d(TAG, "Triggering manual user discovery")
        coroutineScope.launch {
            try {
                // Stop current scanning
                if (isScanning) {
                    bluetoothLeScanner.stopScan(object : ScanCallback() {})
                    isScanning = false
                }
                
                // Start fresh scan
                startScanning()
                
                // Update network status
                _networkStatus.value = _networkStatus.value.copy(
                    lastActivity = System.currentTimeMillis()
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for manual discovery", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger discovery", e)
            }
        }
    }

    /**
     * Send a message through the mesh network
     */
    suspend fun sendMessage(message: MeshMessage): Boolean {
        Log.d(TAG, "Sending mesh message from ${message.senderId} to ${message.receiverId}")
        
        try {
            // Find route to target
            val route = findRoute(message.receiverId)
            if (route == null) {
                Log.w(TAG, "No route found to ${message.receiverId}, initiating route discovery")
                initiateRouteDiscovery(message.receiverId)
                pendingMessages[message.id] = message
                return false
            }

            // Create packet
            val packet = MeshPacket(
                type = PacketType.MESSAGE,
                sourceId = message.senderId,
                targetId = message.receiverId,
                payload = Json.encodeToString(message),
                route = mutableListOf()
            )

            // Send to next hop
            val sent = sendPacketToNextHop(packet, route.nextHopUserId)
            
            if (sent) {
                // Save to database
                meshDatabase.messageDao().insertMessage(message)
                
                // Update statistics
                _networkStatus.value = _networkStatus.value.copy(
                    messagesSent = _networkStatus.value.messagesSent + 1,
                    lastActivity = System.currentTimeMillis()
                )
            }
            
            return sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            return false
        }
    }

    /**
     * Start scanning for mesh peers
     */
    private fun startScanning() {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }
        
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }

        Log.d(TAG, "Starting mesh scanning for service UUID: $MESH_SERVICE_UUID")

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED) // More aggressive for real device discovery
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // Better for finding devices
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT) // Find more devices
            .setReportDelay(0L) // Immediate reporting for real-time discovery
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                Log.d(TAG, "Scan result received: ${result?.device?.address}")
                result?.let { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> Log.e(TAG, "Scan failed: Already started")
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(TAG, "Scan failed: App registration failed")
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "Scan failed: Feature unsupported")
                    SCAN_FAILED_INTERNAL_ERROR -> Log.e(TAG, "Scan failed: Internal error")
                    else -> Log.e(TAG, "Scan failed: Unknown error")
                }
                isScanning = false
            }
        }

        try {
            bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            Log.d(TAG, "Started mesh scanning successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for scanning", e)
            isScanning = false
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning", e)
            isScanning = false
            return
        }

        // Cycle scanning for real device discovery (scan -> pause -> scan)
        coroutineScope.launch {
            delay(SCAN_PERIOD)
            if (isScanning) {
                try {
                    scanCallback?.let { bluetoothLeScanner.stopScan(it) }
                    isScanning = false
                    Log.d(TAG, "Stopped mesh scanning - will restart after pause")
                    
                    // Pause between scan cycles for better discovery and battery life
                    delay(DISCOVERY_INTERVAL)
                    
                    // Restart scanning if Bluetooth is still enabled
                    if (bluetoothAdapter.isEnabled) {
                        Log.d(TAG, "Restarting mesh scanning cycle")
                        startScanning()
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied for stopping scan", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop scanning", e)
                }
            }
        }
    }

    /**
     * Start advertising mesh presence
     */
    private fun startAdvertising(currentUser: MeshUser) {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }
        
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }

        Log.d(TAG, "Starting mesh advertising for user: ${currentUser.username}")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED) // Better discovery for real devices
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM) // Good range vs battery
            .setConnectable(true)
            .setTimeout(0) // Continuous advertising for real device networks
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .addServiceData(ParcelUuid(MESH_SERVICE_UUID), currentUser.username.toByteArray())
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "Mesh advertising started successfully for user: ${currentUser.username}")
                isAdvertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Mesh advertising failed with error code: $errorCode")
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> Log.e(TAG, "Advertise failed: Already started")
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> Log.e(TAG, "Advertise failed: Data too large")
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Log.e(TAG, "Advertise failed: Feature unsupported")
                    ADVERTISE_FAILED_INTERNAL_ERROR -> Log.e(TAG, "Advertise failed: Internal error")
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> Log.e(TAG, "Advertise failed: Too many advertisers")
                    else -> Log.e(TAG, "Advertise failed: Unknown error")
                }
                isAdvertising = false
            }
        }

        try {
            bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Started advertising with callback")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for advertising", e)
            isAdvertising = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            isAdvertising = false
        }

        // Stop advertising after ADVERTISE_PERIOD and restart (continuous advertising)
        coroutineScope.launch {
            delay(ADVERTISE_PERIOD)
            if (isAdvertising) {
                try {
                    advertiseCallback?.let { bluetoothLeAdvertiser.stopAdvertising(it) }
                    isAdvertising = false
                    Log.d(TAG, "Stopped mesh advertising - restarting in 1 second")
                    
                    // Restart advertising after a brief pause
                    delay(1000)
                    if (bluetoothAdapter.isEnabled && currentUser != null) {
                        startAdvertising(currentUser!!)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied for stopping advertising", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during advertising restart", e)
                }
            }
        }
    }

    /**
     * Handle discovered mesh peers
     */
    private fun handleScanResult(result: ScanResult) {
        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
        if (serviceData != null) {
            val username = String(serviceData, StandardCharsets.UTF_8)
            val device = result.device
            
            Log.d(TAG, "Discovered mesh peer: $username at ${device.address}")
            
            // Create or update user
            val meshUser = MeshUser(
                username = username,
                displayName = username,
                bluetoothAddress = device.address,
                lastSeen = System.currentTimeMillis(),
                isOnline = true,
                hops = 1
            )
            
            coroutineScope.launch {
                try {
                    meshDatabase.userDao().insertUser(meshUser)
                    updateDiscoveredUsers()
                    
                    // Attempt connection for message exchange
                    connectToDevice(device, meshUser)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process discovered user", e)
                }
            }
        }
    }

    /**
     * Connect to a mesh peer device
     */
    private fun connectToDevice(device: BluetoothDevice, user: MeshUser) {
        val deviceAddress = device.address
        
        if (connectedDevices.containsKey(deviceAddress)) {
            Log.d(TAG, "Already connected to $deviceAddress")
            return
        }

        // Limit concurrent connections for stability on real devices
        if (connectedDevices.size >= MAX_CONCURRENT_CONNECTIONS) {
            Log.w(TAG, "Max concurrent connections ($MAX_CONCURRENT_CONNECTIONS) reached, skipping $deviceAddress")
            return
        }

        val currentAttempts = connectionAttempts.getOrDefault(deviceAddress, 0)
        if (currentAttempts >= maxRetryAttempts) {
            Log.w(TAG, "Max retry attempts ($maxRetryAttempts) reached for $deviceAddress")
            return
        }

        connectionAttempts[deviceAddress] = currentAttempts + 1
        Log.d(TAG, "Attempting to connect to $deviceAddress (${user.username}) - Attempt ${currentAttempts + 1}/$maxRetryAttempts")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "Successfully connected to ${device.address} (${user.username})")
                            connectedDevices[device.address] = gatt!!
                            
                            // Reset retry counter on successful connection
                            connectionAttempts.remove(device.address)
                            
                            try {
                                gatt.discoverServices()
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Permission denied for service discovery on ${device.address}", e)
                                gatt.disconnect()
                            }
                            updateNetworkStatus()
                        } else {
                            Log.e(TAG, "Connected to ${device.address} but with error status: $status")
                            gatt?.close()
                            // Don't schedule retry here as this will trigger STATE_DISCONNECTED
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected from ${device.address} (${user.username}) - Status: $status")
                        connectedDevices.remove(device.address)
                        gatt?.close()
                        updateNetworkStatus()
                        
                        // Log disconnect reason and handle retries
                        val shouldRetry = when (status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                Log.d(TAG, "Clean disconnect")
                                false // Don't retry on clean disconnect
                            }
                            8 -> {
                                Log.w(TAG, "Connection timeout - will retry")
                                true
                            }
                            19 -> {
                                Log.w(TAG, "Connection terminated by peer device")
                                false
                            }
                            22 -> {
                                Log.w(TAG, "Connection terminated locally")
                                false
                            }
                            133 -> {
                                Log.w(TAG, "Generic GATT error - will retry")
                                true
                            }
                            else -> {
                                Log.w(TAG, "Disconnect with status: $status - will retry")
                                true
                            }
                        }
                        
                        // Schedule retry if appropriate
                        if (shouldRetry) {
                            scheduleConnectionRetry(device, user)
                        } else {
                            // Reset retry counter on clean disconnect
                            connectionAttempts.remove(device.address)
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        Log.d(TAG, "Connecting to ${device.address}...")
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> {
                        Log.d(TAG, "Disconnecting from ${device.address}...")
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Services discovered for ${device.address} (${user.username})")
                    
                    // Look for our mesh service
                    val meshService = gatt?.getService(MESH_SERVICE_UUID)
                    if (meshService != null) {
                        Log.d(TAG, "Found mesh service on ${device.address}")
                        val characteristic = meshService.getCharacteristic(MESH_CHARACTERISTIC_UUID)
                        if (characteristic != null) {
                            Log.d(TAG, "Found mesh characteristic on ${device.address}")
                            // Enable notifications if supported
                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                try {
                                    gatt.setCharacteristicNotification(characteristic, true)
                                    Log.d(TAG, "Enabled notifications for ${device.address}")
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "Permission denied for notifications on ${device.address}", e)
                                }
                            }
                        } else {
                            Log.w(TAG, "Mesh characteristic not found on ${device.address}")
                        }
                    } else {
                        Log.w(TAG, "Mesh service not found on ${device.address}")
                    }
                } else {
                    Log.e(TAG, "Service discovery failed for ${device.address} with status: $status")
                    gatt?.disconnect()
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    Log.d(TAG, "Received data from ${device.address}")
                    handleReceivedData(characteristic.value, user)
                } else {
                    Log.e(TAG, "Failed to read characteristic from ${device.address}, status: $status")
                }
            }
        }

        try {
            val gatt = device.connectGatt(context, false, gattCallback)
            if (gatt == null) {
                Log.e(TAG, "Failed to create GATT connection to ${device.address}")
            } else {
                Log.d(TAG, "GATT connection attempt initiated to ${device.address}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for GATT connection to ${device.address}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during GATT connection to ${device.address}", e)
        }
    }

    /**
     * Schedule a retry for a failed connection
     */
    private fun scheduleConnectionRetry(device: BluetoothDevice, user: MeshUser) {
        val currentAttempts = connectionAttempts.getOrDefault(device.address, 0)
        if (currentAttempts >= maxRetryAttempts) {
            Log.w(TAG, "Not scheduling retry for ${device.address} - max attempts reached")
            connectionAttempts.remove(device.address)
            return
        }
        
        val delayMs = retryDelayMs * currentAttempts // Exponential backoff
        Log.d(TAG, "Scheduling connection retry for ${device.address} in ${delayMs}ms (attempt $currentAttempts)")
        
        coroutineScope.launch {
            delay(delayMs)
            if (!connectedDevices.containsKey(device.address)) {
                Log.d(TAG, "Retrying connection to ${device.address}")
                connectToDevice(device, user)
            }
        }
    }

    /**
     * Handle received mesh data
     */
    private fun handleReceivedData(data: ByteArray, fromUser: MeshUser) {
        try {
            val packetJson = String(data, StandardCharsets.UTF_8)
            val packet = Json.decodeFromString<MeshPacket>(packetJson)
            
            Log.d(TAG, "Received mesh packet type ${packet.type} from ${fromUser.username}")
            
            coroutineScope.launch {
                when (packet.type) {
                    PacketType.MESSAGE -> handleMessage(packet, fromUser)
                    PacketType.ROUTE_REQUEST -> handleRouteRequest(packet, fromUser)
                    PacketType.ROUTE_REPLY -> handleRouteReply(packet, fromUser)
                    PacketType.USER_ANNOUNCEMENT -> handleUserAnnouncement(packet, fromUser)
                    PacketType.HEARTBEAT -> handleHeartbeat(packet, fromUser)
                    PacketType.SOS -> handleSOSMessage(packet, fromUser)
                }
                
                _networkStatus.value = _networkStatus.value.copy(
                    messagesReceived = _networkStatus.value.messagesReceived + 1,
                    lastActivity = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle received data", e)
        }
    }

    /**
     * Handle incoming mesh message
     */
    private suspend fun handleMessage(packet: MeshPacket, fromUser: MeshUser) {
        try {
            val message = Json.decodeFromString<MeshMessage>(packet.payload)
            
            // Check if message is for us
            if (packet.targetId == getCurrentUserId()) {
                // Message delivered to us
                message.copy(isDelivered = true, deliveredAt = System.currentTimeMillis())
                meshDatabase.messageDao().insertMessage(message)
                _messageEvents.emit(message)
                Log.d(TAG, "Message delivered: ${message.content}")
            } else if (packet.ttl > 0) {
                // Forward message
                val route = findRoute(packet.targetId!!)
                if (route != null) {
                    val forwardedPacket = packet.copy(
                        ttl = packet.ttl - 1,
                        route = packet.route.apply { add(getCurrentUserId()) }
                    )
                    sendPacketToNextHop(forwardedPacket, route.nextHopUserId)
                    Log.d(TAG, "Forwarded message to ${route.nextHopUserId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message", e)
        }
    }

    /**
     * Setup GATT server for mesh communication
     */
    private fun setupGattServer() {
        Log.d(TAG, "Setting up GATT server")
        
        val gattServerCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT Server: Device connected ${device?.address}")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT Server: Device disconnected ${device?.address}")
                    }
                }
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "GATT Server: Service added successfully - ${service?.uuid}")
                } else {
                    Log.e(TAG, "GATT Server: Failed to add service, status: $status")
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice?,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic?
            ) {
                Log.d(TAG, "GATT Server: Read request from ${device?.address}")
                // Handle read requests
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                } catch (e: SecurityException) {
                    Log.e(TAG, "Permission denied for GATT response", e)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                Log.d(TAG, "GATT Server: Write request from ${device?.address}")
                // Handle incoming mesh data
                value?.let { data ->
                    Log.d(TAG, "GATT Server: Received ${data.size} bytes from ${device?.address}")
                    val user = MeshUser(
                        username = "unknown",
                        displayName = "Unknown",
                        bluetoothAddress = device?.address
                    )
                    handleReceivedData(data, user)
                }

                if (responseNeeded) {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied for GATT write response", e)
                    }
                }
            }
        }

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to create GATT server")
                return
            }
            
            // Add mesh service
            val meshService = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val meshCharacteristic = BluetoothGattCharacteristic(
                MESH_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or 
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or 
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            meshService.addCharacteristic(meshCharacteristic)
            
            val serviceAdded = gattServer?.addService(meshService)
            if (serviceAdded == true) {
                Log.d(TAG, "GATT Server setup completed successfully")
            } else {
                Log.e(TAG, "Failed to add mesh service to GATT server")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for GATT server setup", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during GATT server setup", e)
        }
    }

    /**
     * Find route to target user
     */
    private fun findRoute(targetUserId: String): MeshRoute? {
        return routingTable[targetUserId]
    }

    /**
     * Send packet to next hop
     */
    private suspend fun sendPacketToNextHop(packet: MeshPacket, nextHopUserId: String): Boolean {
        // Implementation would send packet via Bluetooth to next hop
        // This is a simplified version
        Log.d(TAG, "Sending packet to next hop: $nextHopUserId")
        return true
    }

    /**
     * Initiate route discovery
     */
    private suspend fun initiateRouteDiscovery(targetUserId: String) {
        Log.d(TAG, "Initiating route discovery for: $targetUserId")
        // Implementation would broadcast route request
    }

    /**
     * Handle route request
     */
    private suspend fun handleRouteRequest(packet: MeshPacket, fromUser: MeshUser) {
        // Implementation for route discovery protocol
        Log.d(TAG, "Handling route request from ${fromUser.username}")
    }

    /**
     * Handle route reply
     */
    private suspend fun handleRouteReply(packet: MeshPacket, fromUser: MeshUser) {
        // Implementation for route discovery protocol  
        Log.d(TAG, "Handling route reply from ${fromUser.username}")
    }

    /**
     * Handle user announcement
     */
    private suspend fun handleUserAnnouncement(packet: MeshPacket, fromUser: MeshUser) {
        // Implementation for user discovery
        Log.d(TAG, "Handling user announcement from ${fromUser.username}")
        
        // Store or update user information
        meshDatabase.userDao().insertUser(fromUser)
        updateDiscoveredUsers()
    }

    /**
     * Handle heartbeat
     */
    private suspend fun handleHeartbeat(packet: MeshPacket, fromUser: MeshUser) {
        // Update user last seen time
        meshDatabase.userDao().updateLastSeen(fromUser.id, System.currentTimeMillis())
    }

    /**
     * Handle SOS message
     */
    private suspend fun handleSOSMessage(packet: MeshPacket, fromUser: MeshUser) {
        Log.w(TAG, "SOS message received from ${fromUser.username}")
        val message = Json.decodeFromString<MeshMessage>(packet.payload)
        _messageEvents.emit(message)
    }

    /**
     * Start periodic network discovery
     */
    private fun startPeriodicDiscovery() {
        coroutineScope.launch {
            while (true) {
                delay(30000) // Every 30 seconds
                if (_networkStatus.value.isActive) {
                    startScanning()
                    // Send heartbeat to known users
                    sendHeartbeat()
                }
            }
        }
    }

    /**
     * Send heartbeat to maintain connections
     */
    private suspend fun sendHeartbeat() {
        val users = meshDatabase.userDao().getAllUsers()
        users.forEach { user ->
            if (user.isOnline) {
                val packet = MeshPacket(
                    type = PacketType.HEARTBEAT,
                    sourceId = getCurrentUserId(),
                    targetId = user.id,
                    payload = ""
                )
                // Send heartbeat packet
            }
        }
    }

    /**
     * Update discovered users list
     */
    private suspend fun updateDiscoveredUsers() {
        val users = meshDatabase.userDao().getAllUsers()
        _discoveredUsers.value = users
    }

    /**
     * Update network status
     */
    private fun updateNetworkStatus() {
        _networkStatus.value = _networkStatus.value.copy(
            connectedPeers = connectedDevices.size,
            totalKnownUsers = _discoveredUsers.value.size,
            routingTableSize = routingTable.size
        )
    }

    /**
     * Get current user ID (would be from preferences/database)
     */
    private fun getCurrentUserId(): String {
        // This should return the current user's ID
        return "current_user_id"
    }

    /**
     * Shutdown mesh network gracefully for real device deployment
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down mesh network for real device")
        
        try {
            // Stop scanning and advertising
            isScanning = false
            isAdvertising = false
            
            // Stop scanning if active
            try {
                scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
                Log.d(TAG, "Stopped Bluetooth LE scanning")
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied stopping scan", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
            
            // Stop advertising if active  
            try {
                advertiseCallback?.let { bluetoothLeAdvertiser?.stopAdvertising(it) }
                Log.d(TAG, "Stopped Bluetooth LE advertising")
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied stopping advertising", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping advertising", e)
            }
            
            // Disconnect and close all connections
            connectedDevices.values.forEach { gatt ->
                try {
                    gatt.disconnect()
                    gatt.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing GATT connection", e)
                }
            }
            connectedDevices.clear()
            
            // Close GATT server
            try {
                gattServer?.close()
                gattServer = null
                Log.d(TAG, "Closed GATT server")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing GATT server", e)
            }
            
            // Cancel coroutine scope
            coroutineScope.cancel("Mesh network shutdown")
            
            // Reset network status
            _networkStatus.value = MeshNetworkStatus()
            
            Log.d(TAG, "Mesh network shutdown completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during mesh network shutdown", e)
        }
    }
}