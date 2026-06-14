package com.example.bodycam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
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
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.webrtc.EglBase
import java.io.ByteArrayOutputStream
import java.io.IOException
import android.content.pm.ActivityInfo

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
    private var streamMode = StreamMode.OFFLINE
    private var badNetworkSinceMs: Long? = null
    private var recoveredNetworkSinceMs: Long? = null
    private var isUploadingSnapshot = false
    private val networkStatusHandler = Handler(Looper.getMainLooper())
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val networkStatusRunnable = object : Runnable {
        override fun run() {
            if (isStreaming) {
                val snapshot = currentNetworkSnapshot()
                val targetBitrate = bitrateForQuality(snapshot.quality)
                if (streamMode == StreamMode.LIVE) {
                    webRtcManager.setVideoBitrate(targetBitrate)
                }
                updateFallbackState(snapshot)
                publishStreamNetworkStatus(
                    mode = streamMode.name,
                    networkSnapshot = snapshot,
                    bitrateBps = targetBitrate
                )
                networkStatusHandler.postDelayed(this, NETWORK_STATUS_INTERVAL_MS)
            }
        }
    }
    private val snapshotRunnable = object : Runnable {
        override fun run() {
            if (isStreaming && streamMode == StreamMode.SNAPSHOT) {
                captureAndUploadSnapshot()
                snapshotHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
            }
        }
    }
    private lateinit var firefighterId: String
    private lateinit var missionId: String
    private lateinit var userId : String
    private lateinit var role : String

    private val ip = "100.126.183.52"

    companion object {
        private const val NETWORK_STATUS_INTERVAL_MS = 5_000L
        private const val SNAPSHOT_INTERVAL_MS = 5_000L
        private const val BAD_NETWORK_FALLBACK_MS = 15_000L
        private const val RECOVERY_NETWORK_MS = 30_000L
        private const val SNAPSHOT_JPEG_QUALITY = 70
        private const val GOOD_UPSTREAM_KBPS = 1_000
        private const val WEAK_UPSTREAM_KBPS = 250
    }

    private enum class StreamMode {
        OFFLINE,
        CONNECTING,
        LIVE,
        SNAPSHOT
    }

    private data class NetworkSnapshot(
        val type: String,
        val quality: String,
        val upstreamKbps: Int?,
        val signalStrength: Int?,
        val hasValidatedInternet: Boolean
    )

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

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

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
            onConnected    = {
                runOnUiThread {
                    streamMode = StreamMode.LIVE
                    Toast.makeText(this, "Stream ligado!", Toast.LENGTH_SHORT).show()
                    publishStreamNetworkStatus(
                        mode = StreamMode.LIVE.name,
                        networkSnapshot = currentNetworkSnapshot(),
                        bitrateBps = WebRTCManager.DEFAULT_VIDEO_BITRATE_BPS
                    )
                }
            },
            onDisconnected = {
                runOnUiThread {
                    if (!isStreaming || streamMode == StreamMode.SNAPSHOT) {
                        return@runOnUiThread
                    }

                    publishStreamNetworkStatus(
                        mode = StreamMode.SNAPSHOT.name,
                        networkSnapshot = currentNetworkSnapshot().copy(quality = "BAD"),
                        bitrateBps = null
                    )
                    switchToSnapshotMode()
                }
            },
        )

        // Location
        locationFinder = LocationFinder(this)

        tts = TextToSpeech(this)


        // MQTT
        mqttManager = MqttManager(
            context       = this,
            brokerHost    = ip,
            missionId     = missionId,
            firefighterId = firefighterId,
            isVehicle = role.equals("Vehicle", ignoreCase = true)
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
                telemetryManager.start()
                if (!isVehicle) {
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

    private fun currentNetworkSnapshot(): NetworkSnapshot {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
            ?: return NetworkSnapshot("OFFLINE", "BAD", null, null, false)
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkSnapshot("UNKNOWN", "UNKNOWN", null, null, false)

        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }

        val hasValidatedInternet =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val upstreamKbps = capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 }
        val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.signalStrength
                .takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        } else {
            null
        }

        return NetworkSnapshot(
            type = networkType,
            quality = calculateNetworkQuality(
                hasValidatedInternet = hasValidatedInternet,
                upstreamKbps = upstreamKbps,
                signalStrength = signalStrength
            ),
            upstreamKbps = upstreamKbps,
            signalStrength = signalStrength,
            hasValidatedInternet = hasValidatedInternet
        )
    }

    private fun publishStreamNetworkStatus(
        mode: String,
        networkSnapshot: NetworkSnapshot,
        bitrateBps: Int?
    ) {
        mqttManager.publishNetworkStatus(
            mode = mode,
            quality = networkSnapshot.quality,
            networkType = networkSnapshot.type,
            bitrateBps = bitrateBps,
            upstreamKbps = networkSnapshot.upstreamKbps,
            signalStrength = networkSnapshot.signalStrength,
            hasValidatedInternet = networkSnapshot.hasValidatedInternet
        )
    }

    private fun calculateNetworkQuality(
        hasValidatedInternet: Boolean,
        upstreamKbps: Int?,
        signalStrength: Int?
    ): String {
        if (!hasValidatedInternet) return "BAD"

        if (upstreamKbps != null) {
            return when {
                upstreamKbps >= GOOD_UPSTREAM_KBPS -> "GOOD"
                upstreamKbps >= WEAK_UPSTREAM_KBPS -> "WEAK"
                else -> "BAD"
            }
        }

        if (signalStrength != null) {
            return when {
                signalStrength >= -95 -> "GOOD"
                signalStrength >= -110 -> "WEAK"
                else -> "BAD"
            }
        }

        return "UNKNOWN"
    }

    private fun bitrateForQuality(quality: String): Int {
        return when (quality) {
            "GOOD" -> WebRTCManager.GOOD_VIDEO_BITRATE_BPS
            "BAD" -> WebRTCManager.WEAK_VIDEO_BITRATE_BPS
            else -> WebRTCManager.DEFAULT_VIDEO_BITRATE_BPS
        }
    }

    private fun updateFallbackState(snapshot: NetworkSnapshot) {
        val now = System.currentTimeMillis()

        if (snapshot.quality == "BAD") {
            recoveredNetworkSinceMs = null
            if (badNetworkSinceMs == null) {
                badNetworkSinceMs = now
            }

            if (streamMode == StreamMode.LIVE &&
                now - (badNetworkSinceMs ?: now) >= BAD_NETWORK_FALLBACK_MS
            ) {
                switchToSnapshotMode()
            }
            return
        }

        badNetworkSinceMs = null

        if (snapshot.quality == "GOOD" || snapshot.quality == "WEAK") {
            if (recoveredNetworkSinceMs == null) {
                recoveredNetworkSinceMs = now
            }

            if (streamMode == StreamMode.SNAPSHOT &&
                now - (recoveredNetworkSinceMs ?: now) >= RECOVERY_NETWORK_MS
            ) {
                switchToLiveMode()
            }
        } else {
            recoveredNetworkSinceMs = null
        }
    }

    private fun switchToSnapshotMode() {
        streamMode = StreamMode.SNAPSHOT
        webRtcManager.stopStream()
        stopStreamingApi(firefighterId, missionId)
        startSnapshotUploads()
        publishStreamNetworkStatus(
            mode = StreamMode.SNAPSHOT.name,
            networkSnapshot = currentNetworkSnapshot().copy(quality = "BAD"),
            bitrateBps = null
        )
    }

    private fun switchToLiveMode() {
        stopSnapshotUploads()
        streamMode = StreamMode.LIVE
        webRtcManager.startStream()
        startStreamingApi(firefighterId, missionId)
        publishStreamNetworkStatus(
            mode = StreamMode.LIVE.name,
            networkSnapshot = currentNetworkSnapshot(),
            bitrateBps = WebRTCManager.DEFAULT_VIDEO_BITRATE_BPS
        )
    }

    private fun startSnapshotUploads() {
        snapshotHandler.removeCallbacks(snapshotRunnable)
        snapshotHandler.post(snapshotRunnable)
    }

    private fun stopSnapshotUploads() {
        snapshotHandler.removeCallbacks(snapshotRunnable)
        isUploadingSnapshot = false
    }

    private fun captureAndUploadSnapshot() {
        if (isUploadingSnapshot) return

        val width = localRenderer.width
        val height = localRenderer.height
        if (width <= 0 || height <= 0) return

        isUploadingSnapshot = true
        val targetWidth = minOf(640, width)
        val targetHeight = ((height.toFloat() / width.toFloat()) * targetWidth).toInt()
            .coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        PixelCopy.request(localRenderer, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                uploadSnapshot(bitmap)
            } else {
                bitmap.recycle()
                isUploadingSnapshot = false
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun uploadSnapshot(bitmap: Bitmap) {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_JPEG_QUALITY, output)
        bitmap.recycle()

        val imageBody = output.toByteArray()
            .toRequestBody("image/jpeg".toMediaType())

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("FirefighterID", firefighterId)
            .addFormDataPart("MissionID", missionId)
            .addFormDataPart("Image", "snapshot.jpg", imageBody)
            .build()

        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/firefighter/snapshot")
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                isUploadingSnapshot = false
                android.util.Log.e("BODYCAM", "snapshot upload error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                isUploadingSnapshot = false
            }
        })
    }

    private fun startNetworkStatusUpdates() {
        networkStatusHandler.removeCallbacks(networkStatusRunnable)
        networkStatusHandler.post(networkStatusRunnable)
    }

    private fun stopNetworkStatusUpdates() {
        networkStatusHandler.removeCallbacks(networkStatusRunnable)
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
        streamMode = StreamMode.CONNECTING
        webRtcManager.startStream()
        isStreaming    = true
        btnStream.text = "Parar stream"
        publishStreamNetworkStatus(
            mode = StreamMode.CONNECTING.name,
            networkSnapshot = currentNetworkSnapshot().copy(quality = "UNKNOWN"),
            bitrateBps = WebRTCManager.DEFAULT_VIDEO_BITRATE_BPS
        )
        startNetworkStatusUpdates()

        startStreamingApi(firefighterId, missionId)
        setOnlineStatus(true)

        val intent = Intent(this, ForegroundStreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    private fun handleStreamStop() {
        isStreaming = false
        streamMode = StreamMode.OFFLINE
        stopSnapshotUploads()
        webRtcManager.stopStream()
        handleStreamStopped()

        stopService(Intent(this, ForegroundStreamService::class.java))

        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun handleStreamStopped() {
        isStreaming    = false
        streamMode = StreamMode.OFFLINE
        btnStream.text = "Iniciar stream"
        stopNetworkStatusUpdates()
        stopSnapshotUploads()
        publishStreamNetworkStatus(
            mode = StreamMode.OFFLINE.name,
            networkSnapshot = currentNetworkSnapshot().copy(quality = "UNKNOWN"),
            bitrateBps = null
        )

        stopStreamingApi(firefighterId, missionId)
        setOnlineStatus(false)
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

    private fun setOnlineStatus(online: Boolean) {
        val client = OkHttpClient()

        val body = online.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://$ip:5081/api/User/$userId/status")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM", "Erro ao atualizar status online: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                android.util.Log.d("BODYCAM", "Online status set to $online")
            }
        })
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
        stopNetworkStatusUpdates()
        webRtcManager.release()
        localRenderer.release()
        eglBase.release()

        if (isStreaming) {
            setOnlineStatus(false)
        }
    }
}

private fun Call.enqueue(responseCallback: Any) {}
