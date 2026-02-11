package app.revive.tosave

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class SOSAdvertiser(private val context: Context) {
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    
    private val locationHelper = LocationHelper(context)
    private val handler = Handler(Looper.getMainLooper())
    
    private var isAdvertising = false
    private var currentSOSMessage = ""
    private var sosStartTime = 0L
    
    // Singleton pattern to prevent multiple instances
    companion object {
        private const val TAG = "SOSAdvertiser"
        private val SOS_SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        private const val MANUFACTURER_ID = 0x1234 // Custom manufacturer ID for SOS
        private const val SOS_TIMEOUT_MS = 300000L // 5 minutes timeout for SOS
        private const val SOS_COOLDOWN_MS = 30000L // 30 seconds cooldown between SOS triggers
        
        @Volatile
        private var INSTANCE: SOSAdvertiser? = null
        
        fun getInstance(context: Context): SOSAdvertiser {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SOSAdvertiser(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun stopAllAdvertising() {
            INSTANCE?.stopAdvertising()
        }
    }
    
    // Callback for BLE advertising
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "🎉 BLE advertising started successfully!")
            Log.i(TAG, "Settings: mode=${settingsInEffect.mode}, txPower=${settingsInEffect.txPowerLevel}, timeout=${settingsInEffect.timeout}")
            isAdvertising = true
        }
        
        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error ($errorCode)"
            }
            Log.e(TAG, "❌ BLE advertising failed: $errorMessage")
            isAdvertising = false
            // Try WiFi Direct as fallback
            Log.i(TAG, "Trying WiFi Direct as fallback...")
            startWifiDirectAdvertising()
        }
    }
    
    // Callback for WiFi P2P
    private val wifiP2pActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Log.i(TAG, "WiFi Direct group created successfully")
        }
        
        override fun onFailure(reason: Int) {
            Log.e(TAG, "WiFi Direct group creation failed with reason: $reason")
        }
    }
    
    fun startAdvertising() {
        Log.i(TAG, "=== START ADVERTISING CALLED ===")
        Log.i(TAG, "Current advertising state: $isAdvertising")
        Log.i(TAG, "Time since last SOS: ${System.currentTimeMillis() - sosStartTime}ms")
        
        if (isAdvertising) {
            Log.w(TAG, "SOS already advertising, ignoring duplicate request")
            return
        }
        
        // Check if we recently triggered SOS to prevent spam
        val currentTime = System.currentTimeMillis()
        if (currentTime - sosStartTime < SOS_COOLDOWN_MS) {
            Log.w(TAG, "SOS cooldown active, ignoring request")
            return
        }
        
        sosStartTime = currentTime
        Log.i(TAG, "Starting SOS advertising...")
        
        // Detailed permission checking
        val bluetoothPermOk = hasBluetoothPermissions()
        val wifiPermOk = hasWifiPermissions()
        
        Log.i(TAG, "Bluetooth permissions: $bluetoothPermOk")
        Log.i(TAG, "WiFi permissions: $wifiPermOk")
        
        if (!bluetoothPermOk) {
            Log.e(TAG, "Missing Bluetooth permissions!")
        }
        if (!wifiPermOk) {
            Log.e(TAG, "Missing WiFi permissions!")
        }
        
        // Check Bluetooth state
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null!")
        } else {
            Log.i(TAG, "Bluetooth enabled: ${bluetoothAdapter.isEnabled}")
            Log.i(TAG, "Bluetooth state: ${bluetoothAdapter.state}")
        }
        
        // Get current location and create SOS message
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(TAG, "Getting location for SOS message...")
                val locationInfo = locationHelper.getCurrentLocation()
                currentSOSMessage = createSOSMessage(locationInfo)
                Log.i(TAG, "SOS Message created: $currentSOSMessage")
                
                // Start BLE advertising on main thread
                handler.post {
                    Log.i(TAG, "Attempting to start BLE advertising...")
                    startBLEAdvertising()
                }
                
                // Also try WiFi Direct
                handler.post {
                    Log.i(TAG, "Attempting to start WiFi Direct advertising...")
                    initializeWifiP2p()
                    startWifiDirectAdvertising()
                }
                
                // Auto-stop advertising after timeout if no response
                handler.postDelayed({
                    if (isAdvertising) {
                        Log.i(TAG, "SOS advertising timeout reached, stopping")
                        stopAdvertising()
                    }
                }, SOS_TIMEOUT_MS)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting location for SOS message", e)
                currentSOSMessage = "🚨 HELP! I need immediate assistance. Location unavailable."
                
                handler.post {
                    Log.i(TAG, "Starting advertising with fallback message...")
                    startBLEAdvertising()
                    startWifiDirectAdvertising()
                }
            }
        }
    }
    
    private fun createSOSMessage(locationInfo: LocationHelper.LocationInfo?): String {
        return if (locationInfo != null) {
            "🚨 SOS: I need help, I am at ${locationInfo.latitude}, ${locationInfo.longitude} (${locationInfo.address})"
        } else {
            "🚨 SOS: I need help, I am at an unknown location"
        }
    }
    
    private fun startBLEAdvertising() {
        Log.i(TAG, "=== STARTING BLE ADVERTISING ===")
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions for advertising")
            return
        }
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null - BLE not supported")
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE advertising not supported on this device")
            return
        }
        
        Log.i(TAG, "BLE advertiser obtained, creating settings and data...")
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()
        
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(SOS_SERVICE_UUID))
            .addManufacturerData(MANUFACTURER_ID, currentSOSMessage.toByteArray())
            .build()
        
        Log.i(TAG, "Starting BLE advertising with:")
        Log.i(TAG, "- Service UUID: $SOS_SERVICE_UUID")
        Log.i(TAG, "- Message: $currentSOSMessage")
        Log.i(TAG, "- Message length: ${currentSOSMessage.length} bytes")
        
        try {
            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "BLE startAdvertising() called successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting BLE advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE advertising", e)
        }
    }
    
    private fun initializeWifiP2p() {
        if (!hasWifiPermissions()) {
            Log.e(TAG, "Missing WiFi permissions")
            return
        }
        
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
        wifiP2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
        
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            Log.e(TAG, "WiFi Direct not supported on this device")
        }
    }
    
    private fun startWifiDirectAdvertising() {
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            Log.e(TAG, "WiFi P2P not initialized")
            return
        }
        
        try {
            // Create a WiFi Direct group for discovery
            val config = WifiP2pConfig().apply {
                deviceAddress = ""
                groupOwnerIntent = 15 // High intent to become group owner
            }
            
            wifiP2pManager?.createGroup(wifiP2pChannel, wifiP2pActionListener)
            Log.i(TAG, "Started WiFi Direct group creation for SOS")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting WiFi Direct", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting WiFi Direct", e)
        }
    }
    
    fun stopAdvertising() {
        Log.i(TAG, "Stopping SOS advertising...")
        
        // Stop BLE advertising
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.i(TAG, "BLE advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertising", e)
        }
        
        // Stop WiFi Direct group
        try {
            wifiP2pManager?.removeGroup(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "WiFi Direct group removed")
                }
                
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to remove WiFi Direct group: $reason")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WiFi Direct", e)
        }
        
        isAdvertising = false
        currentSOSMessage = ""
        sosStartTime = 0L
        
        Log.i(TAG, "SOS advertising stopped completely")
    }
    
    fun onSOSAccepted() {
        Log.i(TAG, "SOS was accepted by a responder, stopping advertising")
        stopAdvertising()
    }
    
    fun isCurrentlyAdvertising(): Boolean = isAdvertising
    
    fun getDebugInfo(): String {
        val sb = StringBuilder()
        sb.append("=== SOS Advertiser Debug Info ===\n")
        sb.append("Currently advertising: $isAdvertising\n")
        sb.append("Last SOS time: $sosStartTime\n")
        sb.append("Time since last SOS: ${System.currentTimeMillis() - sosStartTime}ms\n")
        sb.append("Current message: '$currentSOSMessage'\n")
        
        // Bluetooth info
        sb.append("\n--- Bluetooth Status ---\n")
        if (bluetoothAdapter == null) {
            sb.append("Bluetooth adapter: NULL\n")
        } else {
            sb.append("Bluetooth enabled: ${bluetoothAdapter.isEnabled}\n")
            sb.append("Bluetooth state: ${bluetoothAdapter.state}\n")
            sb.append("BLE advertiser: ${bluetoothAdapter.bluetoothLeAdvertiser != null}\n")
        }
        
        // Permission info
        sb.append("\n--- Permissions ---\n")
        sb.append("Bluetooth permissions: ${hasBluetoothPermissions()}\n")
        sb.append("WiFi permissions: ${hasWifiPermissions()}\n")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("BLUETOOTH_ADVERTISE: ${ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED}\n")
            sb.append("BLUETOOTH_CONNECT: ${ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED}\n")
        } else {
            sb.append("BLUETOOTH: ${ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED}\n")
            sb.append("BLUETOOTH_ADMIN: ${ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED}\n")
        }
        sb.append("ACCESS_FINE_LOCATION: ${ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED}\n")
        
        return sb.toString()
    }
    
    fun getCurrentSOSMessage(): String = currentSOSMessage
    
    fun getSOSStartTime(): Long = sosStartTime
    
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun hasWifiPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}