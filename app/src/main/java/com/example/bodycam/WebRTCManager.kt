package com.example.bodycam

import android.content.Context
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*

class WebRTCManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val whipUrl: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    var localVideoTrack: VideoTrack? = null
        private set
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context)
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        // Vídeo
        val videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer = createCameraCapturer()
        videoCapturer?.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            context,
            videoSource.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)
        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)

        // Áudio
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
    }

    fun startStream() {
        createPeerConnection()
        createOffer()
    }

    fun stopStream() {
        peerConnection?.close()
        peerConnection = null
    }

    fun release() {
        stopStream()
        videoCapturer?.stopCapture()
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?.let { return enumerator.createCapturer(it, null) }
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?.let { return enumerator.createCapturer(it, null) }
        return null
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {}
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED    -> onConnected()
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.DISCONNECTED -> onDisconnected()
                        else -> {}
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
                showToast("Erro offer: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun sendWhipOffer(sdpOffer: String) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(whipUrl)
                    .post(sdpOffer.toRequestBody("application/sdp".toMediaType()))
                    .build()

                val response = OkHttpClient().newCall(request).execute()
                val answerSdp = response.body?.string()

                if (response.isSuccessful && answerSdp != null) {
                    peerConnection?.setRemoteDescription(
                        simpleSdpObserver(),
                        SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                    )
                } else {
                    showToast("Erro WHIP: ${response.code}")
                    onDisconnected()
                }
            } catch (e: Exception) {
                showToast("Erro: ${e.message}")
                onDisconnected()
            }
        }.start()
    }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onSetSuccess() {}
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }

    private fun showToast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}