package app.revive.tosave

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class ImprovedPowerButtonReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received intent: ${intent.action}")
        
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
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User present - potential power button press")
                handlePowerButtonPress(context)
            }
        }
    }
    
    private fun handlePowerButtonPress(context: Context) {
        val currentTime = SystemClock.elapsedRealtime()
        
        try {
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
            
            Log.d(TAG, "Power button press registered. Recent presses: ${recentPressTimes.size} within ${DETECTION_WINDOW_MS}ms")
            
            // Check if we have enough presses for SOS trigger
            if (recentPressTimes.size >= REQUIRED_PRESS_COUNT) {
                Log.i(TAG, "Triple power button press detected! Triggering SOS...")
                triggerSOS(context)
                
                // Clear press times after triggering to prevent multiple triggers
                prefs.edit()
                    .putString(KEY_PRESS_TIMES, "")
                    .apply()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling power button press", e)
        }
    }
    
    private fun triggerSOS(context: Context) {
        Log.i(TAG, "=== SOS TRIGGER INITIATED ===")
        
        try {
            // Show immediate feedback to user
            showSOSNotification(context)
            
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
    
    private fun showSOSNotification(context: Context) {
        try {
            // Create an immediate notification to show SOS was triggered
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            val notification = androidx.core.app.NotificationCompat.Builder(context, "sos_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 SOS TRIGGERED!")
                .setContentText("Emergency SOS has been activated")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .build()
            
            notificationManager.notify(9999, notification)
            Log.i(TAG, "SOS notification shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing SOS notification", e)
        }
    }
    
    private fun startSOSService(context: Context) {
        try {
            val serviceIntent = Intent(context, SOSService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "SOS Service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SOS service", e)
        }
    }
    
    companion object {
        private const val TAG = "ImprovedPowerButtonReceiver"
        private const val PREFS_NAME = "improved_power_button_sos_prefs"
        private const val KEY_PRESS_TIMES = "power_button_press_times"
        private const val REQUIRED_PRESS_COUNT = 3
        private const val DETECTION_WINDOW_MS = 3000L // 3 seconds window for triple press (increased for reliability)
    }
}