package com.example.bodycam

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.*

class LocationFinder(private val context : Context) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context);

    var currentLat: Double = 40.2033
    var currentLng: Double = -8.4103

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 1000L
    ).setMinUpdateIntervalMillis(500L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                currentLat = it.latitude
                currentLng = it.longitude
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocationOnce(onResult: (Double, Double) -> Unit) {
        fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            null
        ).addOnSuccessListener { location ->
            if (location != null) {
                currentLat = location.latitude
                currentLng = location.longitude
            }
            onResult(currentLat, currentLng)
        }.addOnFailureListener {
            onResult(currentLat, currentLng)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
    }
}