package com.example.bodycam.sensors

import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt
import kotlin.random.Random

data class SensorData(
    // GPS
    val gpsLat: Double,
    val gpsLng: Double,

    // Biométricos
    val bodyTemp: Double,
    val heartRate: Int,
    val ecgValue: Double,

    // Acelerómetro
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,

    // Giroscópio
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double,

    // Movimento/Queda
    val fallDetected: Boolean,
    val activityState: String,
    val impactMagnitude: Double,
    val orientation: String,

    // Calculados
    val motionLevel: Double,
    val isMoving: Boolean
)

class SensorSimulator(private val onUpdate: (SensorData) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())

    // Estado atual (flutua gradualmente)
    private var currentTemp = 37.0
    private var currentBpm  = 90
    private var currentLat  = 40.2033   // Coimbra (igual ao C#)
    private var currentLng  = -8.4103
    private var ecgPhase    = 0.0       // para simular onda ECG

    private val runnable = object : Runnable {
        override fun run() {
            onUpdate(generateData())
            handler.postDelayed(this, 1000)
        }
    }

    private fun generateData(): SensorData {
        // --- GPS ---
        currentLat += (Random.nextDouble() - 0.5) * 0.0001
        currentLng += (Random.nextDouble() - 0.5) * 0.0001

        // --- Temperatura corporal: 37.0 - 38.5 ---
        currentTemp += Random.nextDouble(-0.1, 0.1)
        currentTemp  = currentTemp.coerceIn(37.0, 38.5)

        // --- Ritmo cardíaco: 90 - 160 bpm ---
        currentBpm += Random.nextInt(-3, 4)
        currentBpm  = currentBpm.coerceIn(90, 160)

        // --- ECG: onda simulada entre 0.0 - 1.2 ---
        ecgPhase += 0.3
        val ecgValue = (Math.sin(ecgPhase) * 0.6 + 0.6).coerceIn(0.0, 1.2)

        // --- Acelerómetro ---
        val accelX = round((Random.nextDouble() - 0.5) * 2)
        val accelY = round((Random.nextDouble() - 0.5) * 2)
        val accelZ = round(9.81 + (Random.nextDouble() - 0.5))  // gravidade + ruído

        // --- Giroscópio ---
        val gyroX = round((Random.nextDouble() - 0.5) * 5)
        val gyroY = round((Random.nextDouble() - 0.5) * 5)
        val gyroZ = round((Random.nextDouble() - 0.5) * 5)

        // --- Nível de movimento (magnitude do acelerómetro) ---
        val motionLevel = round(sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ))
        val isMoving    = motionLevel > 2.0

        // --- Impacto ---
        val impactMagnitude = round(Random.nextDouble() * 1.5)
        val fallDetected    = impactMagnitude > 1.3 // queda se impacto alto

        // --- Estado de atividade ---
        val activityState = when {
            fallDetected       -> "Fall"
            motionLevel > 10.0 -> "Running"
            motionLevel > 5.0  -> "Walking"
            else               -> "Stationary"
        }

        // --- Orientação ---
        val orientation = if (fallDetected) "Horizontal" else "Upright"

        return SensorData(
            gpsLat          = round(currentLat, 6),
            gpsLng          = round(currentLng, 6),
            bodyTemp        = round(currentTemp, 1),
            heartRate       = currentBpm,
            ecgValue        = round(ecgValue, 3),
            accelX          = round(accelX, 3),
            accelY          = round(accelY, 3),
            accelZ          = round(accelZ, 3),
            gyroX           = round(gyroX, 3),
            gyroY           = round(gyroY, 3),
            gyroZ           = round(gyroZ, 3),
            fallDetected    = fallDetected,
            activityState   = activityState,
            impactMagnitude = round(impactMagnitude, 2),
            orientation     = orientation,
            motionLevel     = motionLevel,
            isMoving        = isMoving
        )
    }

    private fun round(value: Double, decimals: Int = 3): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * factor) / factor
    }

    fun start() { handler.post(runnable) }
    fun stop()  { handler.removeCallbacks(runnable) }
}