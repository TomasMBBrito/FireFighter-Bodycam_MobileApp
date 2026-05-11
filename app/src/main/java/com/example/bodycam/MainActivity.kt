package com.example.bodycam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bodycam.sensors.SensorData
import org.webrtc.EglBase

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var btnStream: Button
    private lateinit var localRenderer: org.webrtc.SurfaceViewRenderer
    private lateinit var tvMotion: TextView
    private lateinit var tvAccel: TextView
    private lateinit var tvGyro: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvActivity: TextView

    // Managers
    private lateinit var eglBase: EglBase
    private lateinit var webRtcManager: WebRTCManager
    private lateinit var telemetryManager: TelemetryManager
    private lateinit var mqttManager: MqttManager
    private lateinit var locationFinder: LocationFinder

    private var isStreaming = false
    private lateinit var firefighterId: String
    private lateinit var missionId: String

    private val ip = "192.168.1.136"  //"10.25.36.11"

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraOk   = permissions[Manifest.permission.CAMERA] == true
            val audioOk    = permissions[Manifest.permission.RECORD_AUDIO] == true
            val locationOk = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (locationOk) locationFinder.start()
            if (cameraOk && audioOk) initWebRTC()
            else Toast.makeText(this, "Permissoes necessarias", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRenderer = findViewById(R.id.localRenderer)
        btnStream     = findViewById(R.id.btnStream)
        tvMotion      = findViewById(R.id.tvMotion)
        tvAccel       = findViewById(R.id.tvAccel)
        tvGyro        = findViewById(R.id.tvGyro)
        tvGps         = findViewById(R.id.tvGps)
        tvActivity    = findViewById(R.id.tvActivity)

        firefighterId = intent.getStringExtra("firefighterId") ?: "b0000001-0000-0000-0000-000000000006"
        missionId     = intent.getStringExtra("missionId")     ?: "a0000001-0000-0000-0000-000000000003"

        // EGL
        eglBase = EglBase.create()
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(false)

        // WebRTC
        webRtcManager = WebRTCManager(
            context        = this,
            eglBase        = eglBase,
            whipUrl        = "http://$ip:8889/$firefighterId/whip",
            onConnected    = { runOnUiThread { Toast.makeText(this, "Stream ligado!", Toast.LENGTH_SHORT).show() } },
            onDisconnected = { runOnUiThread { handleStreamStopped() } }
        )

        // Location
        locationFinder = LocationFinder(this)

        // MQTT
        mqttManager = MqttManager(
            context       = this,
            brokerHost    = ip,
            missionId     = missionId,
            firefighterId = firefighterId
        )

        // Telemetry
        telemetryManager = TelemetryManager(
            context  = this,
            location = locationFinder,
            onUpdate = { data ->
                runOnUiThread { updateSensorUI(data) }
                mqttManager.publishTelemetry(data)
            }
        )

        mqttManager.connect(
            onSuccess    = { runOnUiThread { Toast.makeText(this, "MQTT ligado!", Toast.LENGTH_SHORT).show() } },
            onFailure    = { err -> runOnUiThread { Toast.makeText(this, "MQTT erro: $err", Toast.LENGTH_LONG).show() } },
            onRegistered = { telemetryManager.start() }
        )

        btnStream.setOnClickListener {
            if (!isStreaming) handleStreamStart() else handleStreamStop()
        }

        if (hasPermissions()) {
            initWebRTC()
            locationFinder.start()
        } else {
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun initWebRTC() {
        webRtcManager.init()
        webRtcManager.localVideoTrack?.addSink(localRenderer)
    }

    private fun handleStreamStart() {
        webRtcManager.startStream()
        isStreaming    = true
        btnStream.text = "Parar stream"
    }

    private fun handleStreamStop() {
        webRtcManager.stopStream()
        handleStreamStopped()
    }

    private fun handleStreamStopped() {
        isStreaming    = false
        btnStream.text = "Iniciar stream"
    }

    private fun updateSensorUI(data: SensorData) {
        // Accelerometer
        tvAccel.text = "Acel: X${"%.2f".format(data.accelX ?: 0f)} " +
                "Y${"%.2f".format(data.accelY ?: 0f)} " +
                "Z${"%.2f".format(data.accelZ ?: 0f)}"
        tvAccel.setTextColor(Color.WHITE)

        // Gyroscope
        tvGyro.text = "Gyro: X${"%.2f".format(data.gyroX ?: 0f)} " +
                "Y${"%.2f".format(data.gyroY ?: 0f)} " +
                "Z${"%.2f".format(data.gyroZ ?: 0f)}"
        tvGyro.setTextColor(Color.WHITE)

        // GPS
        tvGps.text = "GPS: ${"%.6f".format(data.gpsLat ?: 0.0)}, ${"%.6f".format(data.gpsLng ?: 0.0)}"
        tvGps.setTextColor(Color.WHITE)

        // Motion
        tvMotion.text = "Mov: ${if (data.isMoving == true) "Em movimento" else "Parado"} " +
                "(${"%.1f".format(data.motionLevel ?: 0f)} m/s²)"
        tvMotion.setTextColor(
            if (data.isMoving == true) Color.parseColor("#FFAA00") else Color.WHITE
        )

        // Activity / Orientation
        tvActivity.text = "Estado: ${data.activityState ?: "N/A"} | ${data.orientation ?: "N/A"} | Queda: ${if (data.fallDetected == true) "DETECTADA" else "Nenhuma"}"
        tvActivity.setTextColor(
            if (data.fallDetected == true) Color.parseColor("#FF4444") else Color.WHITE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetryManager.stop()
        locationFinder.stop()
        mqttManager.disconnect()
        webRtcManager.release()
        localRenderer.release()
        eglBase.release()
    }
}