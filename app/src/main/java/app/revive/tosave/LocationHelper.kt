package app.revive.tosave

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(context)
    private val geocoder = Geocoder(context, Locale.getDefault())
    
    data class LocationInfo(
        val latitude: Double,
        val longitude: Double,
        val address: String
    )
    
    suspend fun getCurrentLocation(): LocationInfo? {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            return null
        }
        
        return try {
            val location = getCurrentLocationInternal()
            if (location != null) {
                val address = getAddressFromLocation(location.latitude, location.longitude)
                LocationInfo(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = address
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location", e)
            null
        }
    }
    
    private suspend fun getCurrentLocationInternal(): Location? = suspendCancellableCoroutine { continuation ->
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L // 5 seconds
        ).apply {
            setMaxUpdates(1)
            setWaitForAccurateLocation(false)
        }.build()
        
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                fusedLocationClient.removeLocationUpdates(this)
                continuation.resume(location)
            }
        }
        
        // Try to get last known location first
        fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
            if (lastLocation != null) {
                continuation.resume(lastLocation)
            } else {
                // Request current location
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    context.mainLooper
                )
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Failed to get location", exception)
            continuation.resume(null)
        }
        
        continuation.invokeOnCancellation {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
    
    private fun getAddressFromLocation(latitude: Double, longitude: Double): String {
        return try {
            val addresses: List<Address>? = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                buildString {
                    address.getAddressLine(0)?.let { append(it) }
                    if (isEmpty()) {
                        address.thoroughfare?.let { append(it) }
                        address.subThoroughfare?.let { append(" $it") }
                        address.locality?.let { 
                            if (isNotEmpty()) append(", ")
                            append(it) 
                        }
                        address.adminArea?.let { 
                            if (isNotEmpty()) append(", ")
                            append(it) 
                        }
                        address.countryName?.let { 
                            if (isNotEmpty()) append(", ")
                            append(it) 
                        }
                    }
                }
            } else {
                "Unknown location"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting address", e)
            "Unknown location"
        }
    }
    
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }
    
    companion object {
        private const val TAG = "LocationHelper"
    }
}