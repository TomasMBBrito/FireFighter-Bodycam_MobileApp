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

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val hasAccelerometer = accelerometer != null
    private val hasGyroscope     = gyroscope != null

    // Raw values - null if sensor missing
    private var accelX: Float? = null; private var accelY: Float? = null; private var accelZ: Float? = null
    private var gyroX: Float?  = null; private var gyroY: Float?  = null; private var gyroZ: Float?  = null

    private var freefallDetected = false
    private var lastFallTime     = 0L
    private var lastPublishTime  = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = event.values[0]
                    accelY = event.values[1]
                    accelZ = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = event.values[0]
                    gyroY = event.values[1]
                    gyroZ = event.values[2]
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastPublishTime >= 1000L) {
                lastPublishTime = now
                onUpdate(buildPayload())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d("TelemetryManager", "${sensor?.name} accuracy changed to $accuracy")
        }
    }

    private fun buildPayload(): SensorData {
        // Motion - only calculate if accelerometer has data
        val magnitude = if (accelX != null && accelY != null && accelZ != null) {
            sqrt(accelX!! * accelX!! + accelY!! * accelY!! + accelZ!! * accelZ!!)
        } else null

        val motionLevel = magnitude?.let { abs(it - 9.8f) }
        val isMoving    = motionLevel?.let { it > 0.5f }

        // Fall detection - only if we have magnitude
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

        // Orientation - only if we have data
        val orientation = if (accelX != null && accelY != null && accelZ != null) {
            when {
                accelZ!! > 8f  -> "Face up"
                accelZ!! < -8f -> "Face down"
                accelY!! > 8f  -> "Portrait"
                accelY!! < -8f -> "Portrait reversed"
                accelX!! > 8f  -> "Landscape right"
                else           -> "Landscape left"
            }
        } else null

        // Activity - only if we have motionLevel
        val activityState = motionLevel?.let {
            when {
                it < 0.3f -> "Still"
                it < 2.0f -> "Walking"
                it < 5.0f -> "Running"
                else      -> "High activity"
            }
        }

        return SensorData(
            accelX        = accelX,
            accelY        = accelY,
            accelZ        = accelZ,
            gyroX         = gyroX,
            gyroY         = gyroY,
            gyroZ         = gyroZ,
            motionLevel   = motionLevel,
            isMoving      = isMoving,
            fallDetected  = fallDetected,
            orientation   = orientation,
            activityState = activityState,
            gpsLat        = location.currentLat,
            gpsLng        = location.currentLng
        )
    }

    fun start() {
        accelerometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let     { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}