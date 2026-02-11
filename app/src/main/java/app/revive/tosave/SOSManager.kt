package app.revive.tosave

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SOSManager(private val context: Context) {
    private val locationHelper = LocationHelper(context)
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationManager = 
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    fun triggerEmergency() {
        Log.d(TAG, "Emergency SOS triggered!")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get current location
                val locationInfo = locationHelper.getCurrentLocation()
                
                // Create emergency message
                val emergencyMessage = createEmergencyMessage(locationInfo)
                
                // Get emergency contacts
                val emergencyContacts = getEmergencyContacts()
                
                if (emergencyContacts.isNotEmpty()) {
                    // Send SMS to emergency contacts
                    sendEmergencySMS(emergencyContacts, emergencyMessage)
                    
                    // Show notification
                    showEmergencyNotification(emergencyMessage)
                    
                    Log.i(TAG, "Emergency SOS sent to ${emergencyContacts.size} contacts")
                } else {
                    Log.w(TAG, "No emergency contacts configured")
                    showNoContactsNotification()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during emergency SOS", e)
                showErrorNotification()
            }
        }
    }
    
    private fun createEmergencyMessage(locationInfo: LocationHelper.LocationInfo?): String {
        return if (locationInfo != null) {
            "🚨 HELP! I'm in danger at: ${locationInfo.address} (${locationInfo.latitude}, ${locationInfo.longitude}). Call me NOW!"
        } else {
            "🚨 HELP! I'm in danger but can't get my location. Call me NOW!"
        }
    }
    
    private fun sendEmergencySMS(contacts: List<String>, message: String) {
        if (!hasSMSPermission()) {
            Log.e(TAG, "SMS permission not granted")
            return
        }
        
        try {
            val smsManager = SmsManager.getDefault()
            
            contacts.forEach { phoneNumber ->
                try {
                    // Split long messages if needed
                    val messageParts = smsManager.divideMessage(message)
                    
                    if (messageParts.size == 1) {
                        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(
                            phoneNumber, null, messageParts, null, null
                        )
                    }
                    
                    Log.d(TAG, "Emergency SMS sent to: $phoneNumber")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending emergency SMS", e)
        }
    }
    
    private fun showEmergencyNotification(message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency SOS Sent")
            .setContentText("Emergency message sent to your contacts")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_EMERGENCY, notification)
    }
    
    private fun showNoContactsNotification() {
        val intent = Intent(context, EmergencyContactsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency SOS Failed")
            .setContentText("No emergency contacts configured. Tap to add contacts.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    private fun showErrorNotification() {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Emergency SOS Error")
            .setContentText("Failed to send emergency message. Please try manually.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Emergency SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS notifications"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun getEmergencyContacts(): List<String> {
        val contactsString = sharedPreferences.getString(KEY_EMERGENCY_CONTACTS, "") ?: ""
        return if (contactsString.isNotEmpty()) {
            contactsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    }
    
    fun saveEmergencyContacts(contacts: List<String>) {
        val contactsString = contacts.joinToString(",")
        sharedPreferences.edit()
            .putString(KEY_EMERGENCY_CONTACTS, contactsString)
            .apply()
        
        Log.d(TAG, "Saved ${contacts.size} emergency contacts")
    }
    
    fun addEmergencyContact(phoneNumber: String) {
        val currentContacts = getEmergencyContacts().toMutableList()
        if (!currentContacts.contains(phoneNumber)) {
            currentContacts.add(phoneNumber)
            saveEmergencyContacts(currentContacts)
        }
    }
    
    fun removeEmergencyContact(phoneNumber: String) {
        val currentContacts = getEmergencyContacts().toMutableList()
        currentContacts.remove(phoneNumber)
        saveEmergencyContacts(currentContacts)
    }
    
    private fun hasSMSPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasRequiredPermissions(): Boolean {
        return hasSMSPermission() && 
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
    
    companion object {
        private const val TAG = "SOSManager"
        private const val PREFS_NAME = "sos_preferences"
        private const val KEY_EMERGENCY_CONTACTS = "emergency_contacts"
        private const val NOTIFICATION_CHANNEL_ID = "emergency_sos_channel"
        private const val NOTIFICATION_ID_EMERGENCY = 1001
        private const val NOTIFICATION_ID_ERROR = 1002
    }
}