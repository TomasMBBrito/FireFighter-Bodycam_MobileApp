package com.example.bodycam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.library.rtc.RtcCamera2
import com.pedro.library.view.OpenGlView
import com.pedro.common.ConnectChecker

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var rtmpCamera2: RtcCamera2
    private lateinit var btnStream: Button

    private val streamUrl = "wss://192.168.1.136:8080/live/bodycam"

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraOk = permissions[Manifest.permission.CAMERA] == true
            val audioOk = permissions[Manifest.permission.RECORD_AUDIO] == true

            if (cameraOk && audioOk) {
                findViewById<OpenGlView>(R.id.openGlView).post {
                    startPreview()
                }
            } else {
                Toast.makeText(this, "Permissões necessárias", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val openGlView = findViewById<OpenGlView>(R.id.openGlView)
        btnStream = findViewById(R.id.btnStream)

        rtmpCamera2 = RtcCamera2(openGlView, this)

        btnStream.setOnClickListener {
            if (!rtmpCamera2.isStreaming) {
                startStream()
            } else {
                stopStream()
            }
        }

        openGlView.post {
            if (hasPermissions()) {
                startPreview()
            } else {
                requestPermissions.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                    )
                )
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPreview() {
        if (!rtmpCamera2.isOnPreview) {
            rtmpCamera2.startPreview()
        }
    }

    private fun startStream() {
        if (rtmpCamera2.prepareAudio() && rtmpCamera2.prepareVideo(720, 1280, 60, 1200 * 1024, 2)) {
            rtmpCamera2.startStream(streamUrl)
            btnStream.text = "Parar stream"
        } else {
            Toast.makeText(this, "Erro ao preparar áudio/vídeo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStream() {
        if (rtmpCamera2.isStreaming) {
            rtmpCamera2.stopStream()
        }
        btnStream.text = "Iniciar stream"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStream()
        if (rtmpCamera2.isOnPreview) {
            rtmpCamera2.stopPreview()
        }
    }

    override fun onConnectionStarted(url: String) {
        Toast.makeText(this, "A ligar ao servidor...", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionSuccess() {
        Toast.makeText(this, "Stream iniciado", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectionFailed(reason: String) {
        Toast.makeText(this, "Falhou: $reason", Toast.LENGTH_LONG).show()
        stopStream()
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        Toast.makeText(this, "Stream terminado", Toast.LENGTH_SHORT).show()
    }

    override fun onAuthError() {
        Toast.makeText(this, "Erro de autenticação", Toast.LENGTH_SHORT).show()
    }

    override fun onAuthSuccess() {
        Toast.makeText(this, "Autenticado", Toast.LENGTH_SHORT).show()
    }
}