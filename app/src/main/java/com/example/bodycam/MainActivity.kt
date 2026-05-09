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
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bodycam.sensors.SensorData
import com.example.bodycam.sensors.SensorSimulator
import org.webrtc.EglBase

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var btnStream: Button
    private lateinit var localRenderer: org.webrtc.SurfaceViewRenderer
    private lateinit var tvTemperature: TextView
    private lateinit var tvHeartRate: TextView
    private lateinit var tvMotion: TextView
    private lateinit var tvEcg: TextView
    private lateinit var tvAccel: TextView
    private lateinit var tvGyro: TextView
    private lateinit var tvGps: TextView
    private lateinit var tvActivity: TextView

    // Managers
    private lateinit var eglBase: EglBase
    private lateinit var webRtcManager: WebRTCManager
    private lateinit var sensorSimulator: SensorSimulator
    private lateinit var mqttManager: MqttManager

    private lateinit var locationFinder: LocationFinder

    private var isStreaming = false
    private lateinit var firefighterId: String
    private lateinit var missionId: String

    private val ip : String = "192.168.1.136"//"10.217.231.11"   "10.36.36.11"

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraOk = permissions[Manifest.permission.CAMERA] == true
            val audioOk  = permissions[Manifest.permission.RECORD_AUDIO] == true
            val locationOk = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if(locationOk) locationFinder.start()
            if (cameraOk && audioOk) initWebRTC()
            else Toast.makeText(this, "Permissões necessárias", Toast.LENGTH_SHORT).show()
        }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRenderer = findViewById(R.id.localRenderer)
        btnStream     = findViewById(R.id.btnStream)
        tvTemperature = findViewById(R.id.tvTemperature)
        tvHeartRate   = findViewById(R.id.tvHeartRate)
        tvMotion      = findViewById(R.id.tvMotion)
        tvEcg      = findViewById(R.id.tvEcg)
        tvAccel    = findViewById(R.id.tvAccel)
        tvGyro     = findViewById(R.id.tvGyro)
        tvGps      = findViewById(R.id.tvGps)
        tvActivity = findViewById(R.id.tvActivity)

        // EGL
        eglBase = EglBase.create()
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(false)

        //"http://192.168.1.136:8889/$missionId/$firefighterId/whip"

        firefighterId = intent.getStringExtra("firefighterId") ?: "b0000001-0000-0000-0000-000000000006"
        missionId     = intent.getStringExtra("missionId")     ?: "a0000001-0000-0000-0000-000000000003"


        //192.168.1.136
        // WebRTC Manager
        webRtcManager = WebRTCManager(
            context      = this,
            eglBase      = eglBase,
            whipUrl      = "http://$ip:8889/$missionId/$firefighterId/whip",
            onConnected  = { runOnUiThread { Toast.makeText(this, "Stream ligado!", Toast.LENGTH_SHORT).show() } },
            onDisconnected = { runOnUiThread { handleStreamStopped() } }
        )

        locationFinder = LocationFinder(this);

        // Sensor Simulator
        sensorSimulator = SensorSimulator(
            onUpdate    = { data ->
                runOnUiThread { updateSensorUI(data) }
                mqttManager.publishTelemetry(data)
            },
            location  = locationFinder
        )

        mqttManager = MqttManager(context = this, brokerHost = ip , missionId = missionId , firefighterId = firefighterId)
        mqttManager.connect(
            onSuccess    = { runOnUiThread { Toast.makeText(this, "MQTT ligado!", Toast.LENGTH_SHORT).show() } },
            onFailure    = { err -> runOnUiThread { Toast.makeText(this, "MQTT erro: $err", Toast.LENGTH_LONG).show() } },
            onRegistered = { sensorSimulator.start() }
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
        isStreaming = true
        btnStream.text = "Parar stream"
    }

    private fun handleStreamStop() {
        webRtcManager.stopStream()
        handleStreamStopped()
    }

    private fun handleStreamStopped() {
        isStreaming = false
        btnStream.text = "Iniciar stream"
    }

    private fun updateSensorUI(data: SensorData) {
        // Temperatura
        tvTemperature.text = "🌡 Temp: ${"%.1f".format(data.bodyTemp)}°C"
        tvTemperature.setTextColor(when {
            data.bodyTemp >= 38.0 -> Color.parseColor("#FF4444")
            data.bodyTemp >= 37.5 -> Color.parseColor("#FFAA00")
            else                  -> Color.WHITE
        })

        // ECG / BPM
        tvHeartRate.text = "❤️ ECG: ${data.heartRate} bpm"
        tvHeartRate.setTextColor(when {
            data.heartRate > 140 -> Color.parseColor("#FF4444")
            data.heartRate > 120 -> Color.parseColor("#FFAA00")
            else                 -> Color.WHITE
        })

        // ECG valor
        tvEcg.text = "📈 ECG val: ${data.ecgValue}"
        tvEcg.setTextColor(Color.WHITE)

        // Acelerómetro
        tvAccel.text = "📐 Acel: X${data.accelX} Y${data.accelY} Z${data.accelZ}"
        tvAccel.setTextColor(Color.WHITE)

        // Giroscópio
        tvGyro.text = "🔄 Gyro: X${data.gyroX} Y${data.gyroY} Z${data.gyroZ}"
        tvGyro.setTextColor(Color.WHITE)

        // GPS
        tvGps.text = "📍 ${data.gpsLat}, ${data.gpsLng}"
        tvGps.setTextColor(Color.WHITE)

        // Estado / Queda
        tvActivity.text = "🏃 ${data.activityState} | ${data.orientation}"
        tvActivity.setTextColor(
            if (data.fallDetected) Color.parseColor("#FF4444") else Color.WHITE
        )

        // Movimento
        tvMotion.text = "📡 Mov: ${if (data.isMoving) "Em movimento" else "Parado"} (${"%.1f".format(data.motionLevel)} m/s²)"
        tvMotion.setTextColor(if (data.isMoving) Color.parseColor("#FFAA00") else Color.WHITE)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorSimulator.stop()
        locationFinder.stop()
        mqttManager.disconnect()
        webRtcManager.release()
        localRenderer.release()
        eglBase.release()
    }
}