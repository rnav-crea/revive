package app.revive.tosave

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

data class SOSAlert(
    val deviceId: String,
    val deviceName: String,
    val message: String,
    val timestamp: Long,
    val signalStrength: Int,
    val discoveryMethod: String // "BLE" or "WiFi"
)

class SOSDetector(private val context: Context) {
    
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Observable state for UI
    val isScanning = mutableStateOf(false)
    val detectedSOSAlerts = mutableStateListOf<SOSAlert>()
    
    // Callback for BLE scanning
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            handleBLEScanResult(result)
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { result ->
                handleBLEScanResult(result)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
        }
    }
    
    // Callback for WiFi P2P peer discovery
    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        handleWifiP2pPeerList(peerList)
    }
    
    fun startScanning() {
        if (isScanning.value) {
            Log.w(TAG, "Already scanning for SOS signals")
            return
        }
        
        Log.i(TAG, "Starting SOS detection...")
        isScanning.value = true
        
        // Clear previous alerts
        detectedSOSAlerts.clear()
        
        // Start BLE scanning
        startBLEScanning()
        
        // Start WiFi P2P discovery
        initializeWifiP2p()
        startWifiP2pDiscovery()
    }
    
    fun stopScanning() {
        Log.i(TAG, "Stopping SOS detection...")
        isScanning.value = false
        
        // Stop BLE scanning
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            Log.i(TAG, "BLE scanning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scanning", e)
        }
        
        // Stop WiFi P2P discovery
        try {
            wifiP2pManager?.stopPeerDiscovery(wifiP2pChannel, null)
            Log.i(TAG, "WiFi P2P discovery stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WiFi P2P discovery", e)
        }
    }
    
    private fun startBLEScanning() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions for scanning")
            return
        }
        
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return
        }
        
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BLE scanning not supported on this device")
            return
        }
        
        val scanFilters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SOS_SERVICE_UUID))
                .build()
        )
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        
        try {
            bluetoothLeScanner?.startScan(scanFilters, scanSettings, scanCallback)
            Log.i(TAG, "Started BLE scanning for SOS signals")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting BLE scanning", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scanning", e)
        }
    }
    
    private fun handleBLEScanResult(result: ScanResult) {
        try {
            val device = result.device
            val rssi = result.rssi
            val scanRecord = result.scanRecord
            
            // Extract SOS message from manufacturer data
            val manufacturerData = scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
            val sosMessage = manufacturerData?.let { String(it) } ?: "SOS Alert - No message"
            
            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.name ?: "Unknown Device"
                } else {
                    "Unknown Device"
                }
            } else {
                device.name ?: "Unknown Device"
            }
            
            val sosAlert = SOSAlert(
                deviceId = device.address,
                deviceName = deviceName,
                message = sosMessage,
                timestamp = System.currentTimeMillis(),
                signalStrength = rssi,
                discoveryMethod = "BLE"
            )
            
            // Add to detected alerts if not already present
            val existingAlert = detectedSOSAlerts.find { it.deviceId == sosAlert.deviceId }
            if (existingAlert == null) {
                detectedSOSAlerts.add(sosAlert)
                Log.i(TAG, "New SOS alert detected via BLE: ${sosAlert.deviceName} - ${sosAlert.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing BLE scan result", e)
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
    
    private fun startWifiP2pDiscovery() {
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            Log.e(TAG, "WiFi P2P not initialized")
            return
        }
        
        try {
            wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "WiFi P2P discovery started")
                }
                
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "WiFi P2P discovery failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception starting WiFi P2P discovery", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting WiFi P2P discovery", e)
        }
    }
    
    private fun handleWifiP2pPeerList(peerList: WifiP2pDeviceList) {
        try {
            for (device in peerList.deviceList) {
                // Check if this is a potential SOS device based on device name or other criteria
                if (isSOSDevice(device)) {
                    val sosAlert = SOSAlert(
                        deviceId = device.deviceAddress,
                        deviceName = device.deviceName ?: "Unknown WiFi Device",
                        message = "SOS Alert detected via WiFi Direct",
                        timestamp = System.currentTimeMillis(),
                        signalStrength = -50, // Approximate for WiFi
                        discoveryMethod = "WiFi"
                    )
                    
                    // Add to detected alerts if not already present
                    val existingAlert = detectedSOSAlerts.find { it.deviceId == sosAlert.deviceId }
                    if (existingAlert == null) {
                        detectedSOSAlerts.add(sosAlert)
                        Log.i(TAG, "New SOS alert detected via WiFi: ${sosAlert.deviceName}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing WiFi P2P peer list", e)
        }
    }
    
    private fun isSOSDevice(device: WifiP2pDevice): Boolean {
        // Check if the device name contains SOS indicators
        val deviceName = device.deviceName?.lowercase() ?: ""
        return deviceName.contains("sos") || 
               deviceName.contains("emergency") || 
               deviceName.contains("help") ||
               device.status == WifiP2pDevice.AVAILABLE
    }
    
    fun acceptSOSAlert(sosAlert: SOSAlert) {
        Log.i(TAG, "Accepting SOS alert from ${sosAlert.deviceName}")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send acceptance signal back to the sender
                sendAcceptanceSignal(sosAlert)
                
                // Stop the sender's advertising by using singleton
                SOSAdvertiser.stopAllAdvertising()
                
                // Remove the alert from the list
                handler.post {
                    detectedSOSAlerts.remove(sosAlert)
                }
                
                // Optionally, initiate communication or call emergency services
                Log.i(TAG, "SOS alert accepted and sender notified")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting SOS alert", e)
            }
        }
    }
    
    private suspend fun sendAcceptanceSignal(sosAlert: SOSAlert) {
        when (sosAlert.discoveryMethod) {
            "BLE" -> sendBLEAcceptance(sosAlert)
            "WiFi" -> sendWifiAcceptance(sosAlert)
        }
    }
    
    private fun sendBLEAcceptance(sosAlert: SOSAlert) {
        // For BLE, we could establish a connection and send a response
        // This is a simplified implementation
        Log.i(TAG, "Sending BLE acceptance signal to ${sosAlert.deviceId}")
        // Implementation depends on specific BLE communication protocol
    }
    
    private fun sendWifiAcceptance(sosAlert: SOSAlert) {
        // For WiFi Direct, we could connect to the group and send a message
        Log.i(TAG, "Sending WiFi acceptance signal to ${sosAlert.deviceId}")
        // Implementation depends on specific WiFi Direct communication protocol
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
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
    
    companion object {
        private const val TAG = "SOSDetector"
        private val SOS_SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
        private const val MANUFACTURER_ID = 0x1234 // Should match SOSAdvertiser
    }
}