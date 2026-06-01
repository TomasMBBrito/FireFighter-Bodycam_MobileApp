package com.example.bodycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.bodycam.sensors.SensorData
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.webrtc.EglBase
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var btnStream: Button

    private lateinit var btnLeaveMission: Button
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
    private lateinit var tts: TextToSpeech
    private lateinit var mqttManager: MqttManager
    private lateinit var speechManager: SpeechManager
    private lateinit var locationFinder: LocationFinder

    private lateinit var wakeLock: PowerManager.WakeLock

    private var isStreaming = false
    private lateinit var firefighterId: String
    private lateinit var missionId: String
    private lateinit var userId : String
    private lateinit var role : String

    private val ip = "100.102.144.13"

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
        btnLeaveMission = findViewById(R.id.btnLeaveMission)
        tvMotion      = findViewById(R.id.tvMotion)
        tvAccel       = findViewById(R.id.tvAccel)
        tvGyro        = findViewById(R.id.tvGyro)
        tvGps         = findViewById(R.id.tvGps)
        tvActivity    = findViewById(R.id.tvActivity)

        firefighterId = intent.getStringExtra("firefighterId") ?: "b0000001-0000-0000-0000-000000000006"
        missionId     = intent.getStringExtra("missionId")     ?: "a0000001-0000-0000-0000-000000000003"
        userId = intent.getStringExtra("userId") ?: ""
        role = intent.getStringExtra("role") ?: "Firefighter"

        val isVehicle = role.equals("Vehicle", ignoreCase = true)

        // EGL
        eglBase = EglBase.create()
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(false)

        var whipUrl = "http://$ip:8889/$firefighterId/$missionId/whip"

        speechManager = SpeechManager(this) {
            mqttManager.publishAlert()
        }

        // WebRTC
        webRtcManager = WebRTCManager(
            context        = this,
            eglBase        = eglBase,
            whipUrl        = whipUrl,
            onConnected    = { runOnUiThread { Toast.makeText(this, "Stream ligado!", Toast.LENGTH_SHORT).show() } },
            onDisconnected = { runOnUiThread { handleStreamStopped() } },
        )

        // Location
        locationFinder = LocationFinder(this)

        tts = TextToSpeech(this)


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
            onRegistered = {
                if (!isVehicle) {
                    telemetryManager.start()
                    runOnUiThread { speechManager.start() }
                }
            },
            onTTS = { text -> runOnUiThread { tts.speak(text) } }
        )

        btnStream.setOnClickListener {
            if (!isStreaming) handleStreamStart() else handleStreamStop()
        }

        btnLeaveMission.setOnClickListener {

            if (isStreaming) {
                Toast.makeText(
                    this,
                    "Stop the stream before leaving the mission",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            showLeaveMissionConfirmation()
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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BodyCam::StreamWakeLock"
        )
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun initWebRTC() {
        webRtcManager.init()
        webRtcManager.localVideoTrack?.addSink(localRenderer)
    }

    private fun leaveMission() {

        val client = OkHttpClient()

        val payload = JSONObject().apply {
            put("MissionID", missionId)
            put("FirefighterID", firefighterId)
        }.toString()

        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/leave")
            .post(
                payload.toRequestBody(
                    "application/json".toMediaType()
                )
            )
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {

                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to leave mission",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(
                call: Call,
                response: Response
            ) {

                if (!response.isSuccessful) {

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Error leaving mission",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    return
                }

                runOnUiThread {

                    if (isStreaming)
                        handleStreamStop()

                    val intent =
                        Intent(this@MainActivity,
                            MissionActivity::class.java)

                    intent.putExtra("firefighterId", firefighterId)
                    intent.putExtra("userId", userId)
                    intent.putExtra("role", role)

                    startActivity(intent)

                    finish()
                }
            }
        })
    }

    private fun startStreamingApi(firefighterId: String, missionId: String) {

        val client = OkHttpClient()

        val json = JSONObject().apply {
            put("FirefighterID", firefighterId)
            put("MissionID", missionId)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/firefighter/start-stream")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM", "start stream error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                android.util.Log.d("BODYCAM", "stream started: ${response.code}")
            }
        })
    }

    private fun stopStreamingApi(firefighterId: String, missionId: String) {

        val client = OkHttpClient()

        val json = JSONObject().apply {
            put("FirefighterID", firefighterId)
            put("MissionID", missionId)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/firefighter/stop-stream")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM", "stop stream error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                android.util.Log.d("BODYCAM", "stream stopped: ${response.code}")
            }
        })
    }

    private fun handleStreamStart() {
        webRtcManager.startStream()
        isStreaming    = true
        btnStream.text = "Parar stream"

        startStreamingApi(firefighterId, missionId)

        val intent = Intent(this, ForegroundStreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun handleStreamStop() {
        webRtcManager.stopStream()
        handleStreamStopped()

        stopService(Intent(this, ForegroundStreamService::class.java))

        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun handleStreamStopped() {
        isStreaming    = false
        btnStream.text = "Iniciar stream"

        stopStreamingApi(firefighterId, missionId)
    }

    private fun showLeaveMissionConfirmation() {

        AlertDialog.Builder(this)
            .setTitle("Leave Mission")
            .setMessage("Are you sure you want to leave this mission?")
            .setPositiveButton("Leave") { _, _ ->
                leaveMission()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        speechManager.stop()
        telemetryManager.stop()
        locationFinder.stop()
        mqttManager.disconnect()
        webRtcManager.release()
        localRenderer.release()
        eglBase.release()
    }
}

private fun Call.enqueue(responseCallback: Any) {}
