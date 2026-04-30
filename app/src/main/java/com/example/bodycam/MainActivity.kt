package com.example.bodycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnStream: Button
    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var isStreaming = false

    private val whipUrl = "http://192.168.1.136:8889/bodycam/whip"

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraOk = permissions[Manifest.permission.CAMERA] == true
            val audioOk  = permissions[Manifest.permission.RECORD_AUDIO] == true
            if (cameraOk && audioOk) initWebRTC()
            else Toast.makeText(this, "Permissões necessárias", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localRenderer = findViewById(R.id.localRenderer)
        btnStream     = findViewById(R.id.btnStream)

        eglBase = EglBase.create()
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(false)

        btnStream.setOnClickListener {
            if (!isStreaming) startStream() else stopStream()
        }

        if (hasPermissions()) initWebRTC()
        else requestPermissions.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(applicationContext)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // --- Vídeo ---
        val videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer = createCameraCapturer()
        videoCapturer?.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            applicationContext,
            videoSource.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)
        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)
        localVideoTrack?.addSink(localRenderer)

        // --- Áudio ---
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?.let { return enumerator.createCapturer(it, null) }
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { return enumerator.createCapturer(it, null) }
        return null
    }

    private fun startStream() {
        createPeerConnection()
        createOffer()
        isStreaming = true
        btnStream.text = "Parar stream"
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    runOnUiThread {
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED ->
                                Toast.makeText(this@MainActivity, "Stream ligado!", Toast.LENGTH_SHORT).show()
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.DISCONNECTED -> stopStream()
                            else -> {}
                        }
                    }
                }
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        // ← addTrack() em vez de addStream()
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
                sendWhipOffer(sdp.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erro ao criar offer: $error", Toast.LENGTH_LONG).show()
                }
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun sendWhipOffer(sdpOffer: String) {
        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(whipUrl)
                    .post(sdpOffer.toRequestBody("application/sdp".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val answerSdp = response.body?.string()

                if (response.isSuccessful && answerSdp != null) {
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                    peerConnection?.setRemoteDescription(simpleSdpObserver(), answer)
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Erro WHIP: ${response.code}", Toast.LENGTH_LONG).show()
                        stopStream()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                    stopStream()
                }
            }
        }.start()
    }

    private fun stopStream() {
        peerConnection?.close()
        peerConnection = null
        isStreaming = false
        runOnUiThread { btnStream.text = "Iniciar stream" }
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onSetSuccess() {}
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        videoCapturer?.stopCapture()
        localRenderer.release()
        eglBase.release()
    }
}