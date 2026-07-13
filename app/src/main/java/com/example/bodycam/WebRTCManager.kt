package com.example.bodycam

import android.content.Context
import android.util.Log
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
    private val onDisconnected: () -> Unit,
) {

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    var localVideoTrack: VideoTrack? = null
        private set
    //private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var whipOfferSent = false

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
        videoCapturer?.startCapture(640, 360, 15)
        localVideoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource)

        // Áudio
//        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
//        localAudioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource)
    }

    fun startStream() {
        whipOfferSent = false
        createPeerConnection()
        createOffer()
    }

    fun stopStream() {
        peerConnection?.close()
        peerConnection = null
        whipOfferSent = false
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
        val iceServers = emptyList<PeerConnection.IceServer>()

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
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        peerConnection?.localDescription?.let {
                            sendWhipOfferOnce(it.description)
                        }
                    }
                }
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )

        localVideoTrack?.let {
            peerConnection?.addTrack(
                it,
                listOf("stream0")
            )
        }
        //localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }
    }

    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()

        val h264PayloadTypes = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("H264/90000")) {
                val payload = line.substringAfter("a=rtpmap:")
                    .substringBefore(" ")
                h264PayloadTypes.add(payload)
            }
        }

        if (h264PayloadTypes.isEmpty()) {
            Log.e("WebRTC", "No H264 codec found in SDP")
            return sdp
        }

        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video") }

        if (mLineIndex == -1) return sdp

        val parts = lines[mLineIndex].split(" ").toMutableList()

        val header = parts.take(3)
        val payloads = parts.drop(3)

        val reorderedPayloads =
            h264PayloadTypes + payloads.filter { it !in h264PayloadTypes }

        lines[mLineIndex] =
            (header + reorderedPayloads).joinToString(" ")

        return lines.joinToString("\r\n")
    }


    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val h264Sdp = preferH264(sdp.description)

                val modifiedSdp = SessionDescription(
                    sdp.type,
                    h264Sdp
                )

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendWhipOfferOnce(h264Sdp)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, modifiedSdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                showToast("Erro offer: $error")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun sendWhipOfferOnce(sdpOffer: String) {
        if (whipOfferSent) return
        whipOfferSent = true
        sendWhipOffer(sdpOffer)
    }

    private fun sendWhipOffer(sdpOffer: String) {
        Thread {
            try {
                val request = Request.Builder()
                    .url(whipUrl)
                    .post(sdpOffer.toRequestBody("application/sdp".toMediaType()))
                    .build()

                val response = OkHttpClient().newCall(request).execute()
                Log.d("HTTPWEBRTC", "${response}")
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
