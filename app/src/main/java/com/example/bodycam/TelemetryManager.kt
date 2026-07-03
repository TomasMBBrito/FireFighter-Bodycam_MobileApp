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
    private val bleManager: BleManager,
    private val onUpdate: (SensorData) -> Unit
) {
    private val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var accelX: Float? = null
    private var accelY: Float? = null
    private var accelZ: Float? = null

    private var gyroX: Float? = null
    private var gyroY: Float? = null
    private var gyroZ: Float? = null

    private var compassBearing: Float? = null

    private var freefallDetected = false
    private var lastFallTime = 0L
    private var lastPublishTime = 0L

    init {
        Log.d("TelemetryManager", "RotationVector available: ${rotationVector != null}")
        if (rotationVector == null) {
            Log.e("TelemetryManager", "No rotation vector sensor!")
        }
    }

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
                Sensor.TYPE_ROTATION_VECTOR -> {
                    calculateBearingFromRotationVector(event.values)
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastPublishTime >= 1000L) {
                bleManager.readTemperature()

                lastPublishTime = now
                onUpdate(buildPayload())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d("TelemetryManager", "${sensor?.name} accuracy: $accuracy")
        }
    }

    private fun calculateBearingFromRotationVector(rotationValues: FloatArray) {
        try {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationValues)

            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            var bearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
            compassBearing = (bearing + 360f) % 360f

            Log.d("Telemetry", "Bearing=$compassBearing")
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Rotation vector error", e)
        }
    }

    private fun buildPayload(): SensorData {
        val magnitude = if (accelX != null && accelY != null && accelZ != null) {
            sqrt(accelX!! * accelX!! + accelY!! * accelY!! + accelZ!! * accelZ!!)
        } else null

        val motionLevel = magnitude?.let { abs(it - 9.8f) }
        val isMoving = motionLevel?.let { it > 0.5f }

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

        val activityState = motionLevel?.let {
            when {
                it < 0.3f -> "Still"
                it < 2.0f -> "Walking"
                it < 5.0f -> "Running"
                else -> "High activity"
            }
        }

        val payload = SensorData(
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            motionLevel = motionLevel,
            isMoving = isMoving,
            fallDetected = fallDetected,
            orientation = orientation,
            activityState = activityState,
            gpsLat = location.currentLat,
            gpsLng = location.currentLng,
            compassBearing = compassBearing,

            bodyTemperature = bleManager.latestTemperature
        )

        Log.d("TelemetryManager", "Payload compassBearing: ${payload.compassBearing}")
        return payload
    }

    fun start() {
        accelerometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        rotationVector?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}