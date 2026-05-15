package com.example.bodycam

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.bodycam.sensors.SensorData
import kotlin.math.abs
import kotlin.math.sqrt

class TelemetryManager(
    context: Context,
    private val location: LocationFinder,
    private val onUpdate: (SensorData) -> Unit
) {
    private val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

    // Only need these two sensors
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Raw sensor values
    private var accelX: Float? = null
    private var accelY: Float? = null
    private var accelZ: Float? = null

    private var magnetX: Float? = null
    private var magnetY: Float? = null
    private var magnetZ: Float? = null

    // The bearing we want (0-360 degrees)
    private var compassBearing: Float? = null

    private var freefallDetected = false
    private var lastFallTime = 0L
    private var lastPublishTime = 0L

    init {
        Log.d("TelemetryManager", "Accelerometer available: ${accelerometer != null}")
        Log.d("TelemetryManager", "Magnetometer available: ${magnetometer != null}")

        if (magnetometer == null) {
            Log.e("TelemetryManager", "No magnetometer! Bearing will be null.")
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Store raw values
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = event.values[0]
                    accelY = event.values[1]
                    accelZ = event.values[2]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetX = event.values[0]
                    magnetY = event.values[1]
                    magnetZ = event.values[2]
                }
            }

            // Calculate bearing whenever we have both sensors
            if (accelX != null && accelY != null && accelZ != null &&
                magnetX != null && magnetY != null && magnetZ != null) {
                calculateBearing()
            }

            // Publish every second
            val now = System.currentTimeMillis()
            if (now - lastPublishTime >= 1000L) {
                lastPublishTime = now
                onUpdate(buildPayload())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d("TelemetryManager", "${sensor?.name} accuracy: $accuracy")
        }
    }

    private fun calculateBearing() {
        try {
            val gravity = floatArrayOf(accelX!!, accelY!!, accelZ!!)
            val geomagnetic = floatArrayOf(magnetX!!, magnetY!!, magnetZ!!)

            val R = FloatArray(9)
            val I = FloatArray(9)

            // Get rotation matrix from accelerometer and magnetometer
            val success = SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)

            if (success) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)

                // orientation[0] = azimuth (bearing) in radians
                var bearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
                bearing = (bearing + 360) % 360  // Normalize to 0-360

                compassBearing = bearing

                // Debug log - remove in production
                Log.d("TelemetryManager", "Bearing: ${"%.1f".format(bearing)}°")
            }
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Error calculating bearing", e)
        }
    }

    private fun buildPayload(): SensorData {
        // Calculate motion level
        val magnitude = if (accelX != null && accelY != null && accelZ != null) {
            sqrt(accelX!! * accelX!! + accelY!! * accelY!! + accelZ!! * accelZ!!)
        } else null

        val motionLevel = magnitude?.let { abs(it - 9.8f) }
        val isMoving = motionLevel?.let { it > 0.5f }

        // Fall detection
        val fallDetected = if (magnitude != null) {
            val now = System.currentTimeMillis()
            if (magnitude < 2.0f) {
                freefallDetected = true
                lastFallTime = now
            }
            if (freefallDetected && magnitude > 20.0f && (now - lastFallTime) < 500) {
                freefallDetected = false
                true
            } else {
                if (now - lastFallTime > 2000) freefallDetected = false
                false
            }
        } else null

        // Device orientation
        val orientation = if (accelX != null && accelY != null && accelZ != null) {
            when {
                accelZ!! > 8f -> "Face up"
                accelZ!! < -8f -> "Face down"
                accelY!! > 8f -> "Portrait"
                accelY!! < -8f -> "Portrait reversed"
                accelX!! > 8f -> "Landscape right"
                else -> "Landscape left"
            }
        } else null

        // Activity state
        val activityState = motionLevel?.let {
            when {
                it < 0.3f -> "Still"
                it < 2.0f -> "Walking"
                it < 5.0f -> "Running"
                else -> "High activity"
            }
        }

        return SensorData(
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = null,
            gyroY = null,
            gyroZ = null,
            motionLevel = motionLevel,
            isMoving = isMoving,
            fallDetected = fallDetected,
            orientation = orientation,
            activityState = activityState,
            gpsLat = location.currentLat,
            gpsLng = location.currentLng,
            compassBearing = compassBearing  // This will now have a value!
        )
    }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}