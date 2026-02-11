package app.revive.tosave

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class SOSService : Service() {
    
    private lateinit var powerButtonReceiver: PowerButtonReceiver
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SOS Service created")
        
        // Initialize context provider
        ApplicationContextProvider.initialize(this)
        
        // Initialize power button receiver
        powerButtonReceiver = PowerButtonReceiver()
        
        // Acquire wake lock to keep service running
        acquireWakeLock()
        
        // Register for power button events
        registerPowerButtonReceiver()
        
        // Start foreground service
        startForegroundService()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SOS Service started")
        return START_STICKY // Restart service if killed
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SOS Service destroyed")
        
        // Cleanup
        try {
            unregisterReceiver(powerButtonReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering power button receiver", e)
        }
        wakeLock?.release()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun startForegroundService() {
        createNotificationChannel()
        
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Emergency SOS Active")
            .setContentText("Press power button 3 times quickly for emergency")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Emergency SOS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps emergency SOS monitoring active"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SOSService::WakeLock"
        )
        wakeLock?.acquire(10*60*1000L /*10 minutes*/)
    }
    
    private fun registerPowerButtonReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(powerButtonReceiver, intentFilter)
        Log.d(TAG, "Power button receiver registered")
    }
    
    fun notifySOSTriggered() {
        // Called when SOS is triggered to update notification
        updateNotificationForEmergency()
    }
    
    private fun updateNotificationForEmergency() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Emergency SOS Triggered!")
            .setContentText("SOS advertising started - nearby devices can respond")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Reset to normal notification after 30 seconds
        android.os.Handler(mainLooper).postDelayed({
            startForegroundService()
        }, 30000)
    }
    
    companion object {
        private const val TAG = "SOSService"
        private const val NOTIFICATION_CHANNEL_ID = "sos_service_channel"
        private const val NOTIFICATION_ID = 2001
    }
}