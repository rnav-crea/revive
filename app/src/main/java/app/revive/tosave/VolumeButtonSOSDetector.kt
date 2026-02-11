package app.revive.tosave

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

class VolumeButtonSOSDetector(private val context: Context) {
    
    private var lastVolumeTime = 0L
    private var volumePressCount = 0
    
    fun handleVolumeButtonPress(): Boolean {
        val currentTime = SystemClock.elapsedRealtime()
        
        // Reset count if too much time has passed
        if (currentTime - lastVolumeTime > DETECTION_WINDOW_MS) {
            volumePressCount = 0
        }
        
        volumePressCount++
        lastVolumeTime = currentTime
        
        Log.d(TAG, "Volume button press count: $volumePressCount")
        
        if (volumePressCount >= REQUIRED_PRESS_COUNT) {
            Log.i(TAG, "Volume button SOS pattern detected!")
            triggerSOS()
            volumePressCount = 0
            return true // Consume the event
        }
        
        return false // Don't consume the event
    }
    
    private fun triggerSOS() {
        Log.i(TAG, "=== VOLUME BUTTON SOS TRIGGERED ===")
        
        try {
            val sosAdvertiser = SOSAdvertiser.getInstance(context)
            
            if (sosAdvertiser.isCurrentlyAdvertising()) {
                Log.w(TAG, "SOS already active")
                return
            }
            
            // Start BLE/WiFi SOS advertising
            sosAdvertiser.startAdvertising()
            Log.i(TAG, "SOS advertising started via volume button")
            
            // ALSO start Nearby Connections for chat functionality
            try {
                // We need to get the NearbyManager instance if available
                Log.i(TAG, "Attempting to start chat advertising for emergency communication...")
                // Note: This would require MainActivity to provide NearbyManager access
                // For now, we'll trigger via broadcast intent
                val broadcastIntent = android.content.Intent("app.revive.tosave.START_EMERGENCY_CHAT")
                context.sendBroadcast(broadcastIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting emergency chat advertising", e)
            }
            
            // Also trigger SMS fallback
            val sosManager = SOSManager(context)
            sosManager.triggerEmergency()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering volume button SOS", e)
        }
    }
    
    companion object {
        private const val TAG = "VolumeButtonSOSDetector"
        private const val REQUIRED_PRESS_COUNT = 5 // 5 quick volume presses
        private const val DETECTION_WINDOW_MS = 3000L // 3 seconds
    }
}