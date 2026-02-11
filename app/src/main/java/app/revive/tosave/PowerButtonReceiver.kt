package app.revive.tosave

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen turned on - Power button pressed")
                handlePowerButtonPress(context)
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned off - Power button pressed")
                handlePowerButtonPress(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, starting SOS service")
                startSOSService(context)
            }
        }
    }
    
    private fun handlePowerButtonPress(context: Context) {
        val currentTime = SystemClock.elapsedRealtime()
        
        // Get stored press times from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedTimesString = prefs.getString(KEY_PRESS_TIMES, "") ?: ""
        
        // Parse existing press times
        val pressTimes = if (storedTimesString.isNotEmpty()) {
            storedTimesString.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
        } else {
            mutableListOf()
        }
        
        // Add current press time
        pressTimes.add(currentTime)
        
        // Filter out old press times (older than detection window)
        val recentPressTimes = pressTimes.filter { 
            currentTime - it <= DETECTION_WINDOW_MS 
        }
        
        // Store updated press times
        val updatedTimesString = recentPressTimes.joinToString(",")
        prefs.edit()
            .putString(KEY_PRESS_TIMES, updatedTimesString)
            .apply()
        
        Log.d(TAG, "Power button press registered. Recent presses: ${recentPressTimes.size}")
        
        // Check if we have enough presses for SOS trigger
        if (recentPressTimes.size >= REQUIRED_PRESS_COUNT) {
            Log.i(TAG, "Triple power button press detected! Triggering SOS...")
            triggerSOS(context)
            
            // Clear press times after triggering to prevent multiple triggers
            prefs.edit()
                .putString(KEY_PRESS_TIMES, "")
                .apply()
        }
    }
    
    private fun triggerSOS(context: Context) {
        Log.i(TAG, "=== POWER BUTTON SOS TRIGGERED ===")
        
        try {
            // Use singleton instance to prevent multiple advertisers
            val sosAdvertiser = SOSAdvertiser.getInstance(context)
            
            // Check if already advertising to prevent duplicates
            if (sosAdvertiser.isCurrentlyAdvertising()) {
                Log.w(TAG, "SOS already active, ignoring trigger")
                return
            }
            
            // Start BLE/WiFi SOS advertising
            sosAdvertiser.startAdvertising()
            Log.i(TAG, "SOS advertising started successfully")
            
            // ALSO start Nearby Connections for emergency chat
            try {
                Log.i(TAG, "Starting emergency chat advertising...")
                val broadcastIntent = android.content.Intent("app.revive.tosave.START_EMERGENCY_CHAT")
                context.sendBroadcast(broadcastIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting emergency chat advertising", e)
            }
            
            // ALSO trigger SMS messaging to emergency contacts
            val sosManager = SOSManager(context)
            sosManager.triggerEmergency()
            Log.i(TAG, "Emergency SMS messaging triggered")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SOS advertising", e)
            
            // Fallback to traditional SMS method if advertising fails
            try {
                val sosManager = SOSManager(context)
                sosManager.triggerEmergency()
                Log.i(TAG, "Fallback SMS SOS triggered")
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback SOS also failed", fallbackError)
            }
        }
    }
    
    private fun startSOSService(context: Context) {
        try {
            val serviceIntent = Intent(context, SOSService::class.java)
            context.startForegroundService(serviceIntent)
            Log.d(TAG, "SOS Service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SOS service", e)
        }
    }
    
    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val PREFS_NAME = "power_button_sos_prefs"
        private const val KEY_PRESS_TIMES = "power_button_press_times"
        private const val REQUIRED_PRESS_COUNT = 3
        private const val DETECTION_WINDOW_MS = 2000L // 2 seconds window for triple press
    }
}