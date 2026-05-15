package com.example.bodycam

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.bodycam.sensors.SensorData
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import org.json.JSONObject
import java.util.UUID

class MqttManager(
    private val context: Context,
    private val brokerHost: String,
    private val brokerPort: Int       = 1883,
    private val firefighterId: String,
    private val missionId: String,
    private val deviceId: String      = "d2719c1c-8f1b-4b4e-9b5e-1c2f3a4b5c6d"
) {
    private var client: Mqtt3AsyncClient? = null
    private var isConnected = false

    private val telemetryTopic = "$firefighterId/telemetry"

    fun connect(onSuccess: () -> Unit, onFailure: (String) -> Unit, onRegistered: () -> Unit) {
        client = MqttClient.builder()
            .useMqttVersion3()
            .identifier(UUID.randomUUID().toString())
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .buildAsync()

        client?.connectWith()
            ?.cleanSession(true)
            ?.send()
            ?.whenComplete { _, error ->
                if (error != null) {
                    Log.e("MQTT", "Falha na ligação: ${error.message}")
                    onFailure(error.message ?: "Erro desconhecido")
                } else {
                    Log.d("MQTT", "Ligado ao broker")
                    isConnected = true
                    register { onRegistered() }
                    onSuccess()
                }
            }
    }

    private fun register(onRegistered: () -> Unit) {
        val payload = JSONObject().apply {
            put("MissionId",     missionId)
            put("DeviceId",      deviceId)
            if (firefighterId.isNotEmpty() && firefighterId != "null") {
                put("FirefighterId", firefighterId)
            } else {
                put("FirefighterId", JSONObject.NULL)
            }
        }.toString()

        client?.publishWith()
            ?.topic("firefighter/register")
            ?.payload(payload.toByteArray())
            ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, error ->
                if (error == null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        onRegistered()
                    }, 500)
                } else {
                    Log.e("MQTT", "Erro ao registar: ${error.message}")
                }
            }
        Log.d("MQTT", "Bombeiro registado")
    }

    private fun deregister() {
        val payload = JSONObject().apply {
            put("MissionId",     missionId)
            put("FirefighterId", firefighterId)
        }.toString()

        client?.publishWith()
            ?.topic("firefighter/deregister")
            ?.payload(payload.toByteArray())
            ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, error ->
                if (error != null) Log.e("MQTT", "Erro ao desregistar: ${error.message}")
            }
        Log.d("MQTT", "Bombeiro desregistado")
    }

    fun publishTelemetry(data: SensorData) {
        if (!isConnected) return

        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.Instant.now().toString()
        } else {
            java.util.Date().toString()
        }

        val payload = JSONObject().apply {
            put("DeviceId",      deviceId)
            put("MissionId", missionId)
            put("Timestamp",     timestamp)
            put("GpsLat",        data.gpsLat)
            put("GpsLng",        data.gpsLng)
            put("AccelX",        data.accelX)
            put("AccelY",        data.accelY)
            put("AccelZ",        data.accelZ)
            put("GyroX",         data.gyroX)
            put("GyroY",         data.gyroY)
            put("GyroZ",         data.gyroZ)
            put("MotionLevel",   data.motionLevel)
            put("IsMoving",      data.isMoving)
            put("FallDetected",  data.fallDetected)
            put("Orientation",   data.orientation)
            put("ActivityState", data.activityState)
        }.toString()

        publish(telemetryTopic, payload, qos = 2)
        Log.d("MQTT", "Telemetria enviada — Activity: ${data.activityState}")
    }

    private fun publish(topic: String, payload: String, qos: Int) {
        val qosLevel = when (qos) {
            1    -> com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE
            2    -> com.hivemq.client.mqtt.datatypes.MqttQos.EXACTLY_ONCE
            else -> com.hivemq.client.mqtt.datatypes.MqttQos.AT_MOST_ONCE
        }

        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray())
            ?.qos(qosLevel)
            ?.send()
            ?.whenComplete { _, error ->
                if (error != null) Log.e("MQTT", "Erro ao publicar: ${error.message}")
            }
    }

    fun disconnect() {
        deregister()
        client?.disconnect()
        isConnected = false
        Log.d("MQTT", "Desligado do broker")
    }
}