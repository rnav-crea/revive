package app.revive.tosave

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

class SOSReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen turned on")
                // Screen turned on, could be power button press
                registerButtonPressTime()
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned off")
                // Screen turned off, power button press
                registerButtonPressTime()
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, starting SOS service")
                startSOSService(context)
            }
        }
    }
    
    private fun registerButtonPressTime() {
        val currentTime = SystemClock.elapsedRealtime()
        
        // Get stored press times
        val pressTimes = getStoredPressTimes().toMutableList()
        
        // Add current press time
        pressTimes.add(currentTime)
        
        // Remove old press times (older than 2 seconds)
        val recentPressTimes = pressTimes.filter { currentTime - it <= DETECTION_WINDOW_MS }
        
        // Store updated press times
        storePressTimes(recentPressTimes)
        
        Log.d(TAG, "Button press registered. Recent presses: ${recentPressTimes.size}")
        
        // Check if we have 3 presses within the detection window
        if (recentPressTimes.size >= REQUIRED_PRESS_COUNT) {
            Log.i(TAG, "Emergency SOS pattern detected!")
            triggerEmergency()
            // Clear press times after triggering
            storePressTimes(emptyList())
        }
    }
    
    private fun triggerEmergency() {
        try {
            val context = ApplicationContextProvider.getContext()
            context?.let {
                val sosManager = SOSManager(it)
                sosManager.triggerEmergency()
            } ?: run {
                Log.e(TAG, "Context not available for emergency trigger")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering emergency", e)
        }
    }
    
    private fun getStoredPressTimes(): List<Long> {
        val context = ApplicationContextProvider.getContext() ?: return emptyList()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pressTimesString = prefs.getString(KEY_PRESS_TIMES, "") ?: ""
        
        return if (pressTimesString.isNotEmpty()) {
            pressTimesString.split(",").mapNotNull { it.toLongOrNull() }
        } else {
            emptyList()
        }
    }
    
    private fun storePressTimes(pressTimes: List<Long>) {
        val context = ApplicationContextProvider.getContext() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pressTimesString = pressTimes.joinToString(",")
        
        prefs.edit()
            .putString(KEY_PRESS_TIMES, pressTimesString)
            .apply()
    }
    
    private fun startSOSService(context: Context) {
        try {
            val serviceIntent = Intent(context, SOSService::class.java)
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SOS service", e)
        }
    }
    
    companion object {
        private const val TAG = "SOSReceiver"
        private const val PREFS_NAME = "sos_button_prefs"
        private const val KEY_PRESS_TIMES = "button_press_times"
        private const val REQUIRED_PRESS_COUNT = 3
        private const val DETECTION_WINDOW_MS = 2000L // 2 seconds
    }
}

// Application context provider to access context from static methods
object ApplicationContextProvider {
    private var context: Context? = null
    
    fun initialize(context: Context) {
        this.context = context.applicationContext
    }
    
    fun getContext(): Context? = context
}