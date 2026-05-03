package com.example.bodycam

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.bodycam.sensors.SensorData
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class MqttManager(
    private val context: Context,
    private val brokerHost: String = "192.168.1.136",
    private val brokerPort: Int    = 1883,
    private val firefighterId: String = "b0000001-0000-0000-0000-000000000006",
    private val missionId: String     = "a0000001-0000-0000-0000-000000000003",
    private val deviceId: String      = "d2719c1c-8f1b-4b4e-9b5e-1c2f3a4b5c6d"
) {
    private var client: Mqtt3AsyncClient? = null
    private var isConnected = false

    private val telemetryTopic = "$missionId/$firefighterId/telemetry"

    fun connect(onSuccess: () -> Unit, onFailure: (String) -> Unit,onRegistered: () -> Unit) {
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
            put("MissionId", missionId)
            put("FirefighterId", firefighterId)
            put("DeviceId", deviceId)
        }.toString()

        client?.publishWith()
            ?.topic("firefighter/register")
            ?.payload(payload.toByteArray())
            ?.qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
            ?.send()
            ?.whenComplete { _, error ->
                if (error == null) {
                    Thread.sleep(500) // ← delay para o backend subscrever
                    onRegistered()
                }
            }
        Log.d("MQTT", "Bombeiro registado")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun publishTelemetry(data: SensorData) {
        if (!isConnected) return

        val payload = JSONObject().apply {
            put("DeviceId",         deviceId)
            put("Timestamp", Instant.now().toString())
            put("GpsLat",           data.gpsLat)
            put("GpsLng",           data.gpsLng)
            put("BodyTemp",         data.bodyTemp)
            put("HeartRate",        data.heartRate)
            put("EcgValue",         data.ecgValue)
            put("AccelX",           data.accelX)
            put("AccelY",           data.accelY)
            put("AccelZ",           data.accelZ)
            put("GyroX",            data.gyroX)
            put("GyroY",            data.gyroY)
            put("GyroZ",            data.gyroZ)
            put("FallDetected",     data.fallDetected)
            put("ActivityState",    data.activityState)
            put("ImpactMagnitude",  data.impactMagnitude)
            put("Orientation",      data.orientation)
        }.toString()

        publish(telemetryTopic, payload, qos = 2)
        Log.d("MQTT", "Telemetria enviada — HR: ${data.heartRate}")
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
        client?.disconnect()
        isConnected = false
        Log.d("MQTT", "Desligado do broker")
    }
}