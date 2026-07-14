package com.example.bodycam.sensors

data class SensorData(
    // GPS
    val gpsLat: Double?,
    val gpsLng: Double?,

    // Accelerometer
    val accelX: Float?,
    val accelY: Float?,
    val accelZ: Float?,

    // Gyroscope
    val gyroX: Float?,
    val gyroY: Float?,
    val gyroZ: Float?,

    // Motion / Fall
    val motionLevel: Float?,
    //val isMoving: Boolean?,
    val activityState: String?,

    val compassBearing: Float?,

    // BLE Sensors
    val bodyTemperature: Float?,
    val heartRate: Int?,
)
