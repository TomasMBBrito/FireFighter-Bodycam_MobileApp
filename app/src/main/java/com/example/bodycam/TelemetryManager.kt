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
    private var lastPublishTime = 0L

    private val motionBuffer = ArrayDeque<Float>()
    private val bufferWindowSize = 15

    //private var currentlyMoving = false
    private val movingEnterThreshold = 0.6f
    private val movingExitThreshold = 0.3f

    private var pendingActivityState: String? = null
    private var pendingActivityCount = 0
    private var confirmedActivityState: String? = null
    private val activityConfirmCount = 3

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
                    updateMotionBuffer()
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
                bleManager.readSensorData()

                lastPublishTime = now
                onUpdate(buildPayload())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d("TelemetryManager", "${sensor?.name} accuracy: $accuracy")
        }
    }

    private fun updateMotionBuffer() {
        val x = accelX ?: return
        val y = accelY ?: return
        val z = accelZ ?: return

        val magnitude = sqrt(x * x + y * y + z * z)
        val instantMotion = abs(magnitude - 9.8f)

        motionBuffer.addLast(instantMotion)
        if (motionBuffer.size > bufferWindowSize) {
            motionBuffer.removeFirst()
        }
    }

    private fun smoothedMotionLevel(): Float? {
        if (motionBuffer.isEmpty()) return null
        return motionBuffer.average().toFloat()
    }

//    private fun updateIsMoving(smoothed: Float?): Boolean? {
//        if (smoothed == null) return null
//        currentlyMoving = if (currentlyMoving) {
//            smoothed > movingExitThreshold
//        } else {
//            smoothed > movingEnterThreshold
//        }
//        return currentlyMoving
//    }

    private fun updateActivityState(smoothed: Float?): String? {
        if (smoothed == null) return confirmedActivityState

        val candidate = when {
            smoothed < 0.3f -> "Still"
            smoothed < 2.0f -> "Walking"
            smoothed < 5.0f -> "Running"
            else -> "High activity"
        }

        if (candidate == pendingActivityState) {
            pendingActivityCount++
        } else {
            pendingActivityState = candidate
            pendingActivityCount = 1
        }

        if (pendingActivityCount >= activityConfirmCount) {
            confirmedActivityState = candidate
        }

        return confirmedActivityState
    }

    private fun calculateBearingFromRotationVector(rotationValues: FloatArray) {
        try {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationValues)

            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            var bearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
            compassBearing = (bearing + 360f) % 360f

            //Log.d("Telemetry", "Bearing=$compassBearing")
        } catch (e: Exception) {
            Log.e("TelemetryManager", "Rotation vector error", e)
        }
    }

    private fun buildPayload(): SensorData {
        val motionLevel = smoothedMotionLevel()
        //val isMoving = updateIsMoving(motionLevel)
        val activityState = updateActivityState(motionLevel)

        Log.d("Telemetry", "Latest HR = ${bleManager.latestHeartRate}")

        val payload = SensorData(
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            motionLevel = motionLevel,
            //isMoving = isMoving,
            activityState = activityState,
            gpsLat = location.currentLat,
            gpsLng = location.currentLng,
            compassBearing = compassBearing,

            bodyTemperature = bleManager.latestTemperature,
            heartRate = bleManager.latestHeartRate,
        )

        //Log.d("TelemetryManager", "Payload compassBearing: ${payload.compassBearing}")
        return payload
    }

    fun start() {
        accelerometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        rotationVector?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        motionBuffer.clear()
        pendingActivityState = null
        pendingActivityCount = 0
        confirmedActivityState = null
        //currentlyMoving = false
    }
}