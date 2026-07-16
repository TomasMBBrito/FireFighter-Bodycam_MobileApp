package com.example.bodycam

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class MissionActivity : AppCompatActivity() {

    private val ip = "100.126.183.52"

    private var firefighterId: String = ""
    private var firefighterName: String = ""
    private var role: String = "Firefighter"
    private var userId: String = ""
    private var token: String = ""
    private var existingMissionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        firefighterId = intent.getStringExtra("firefighterId") ?: ""
        firefighterName = intent.getStringExtra("firefighterName") ?: ""
        role = intent.getStringExtra("role") ?: "Firefighter"
        userId = intent.getStringExtra("userId") ?: ""
        token = intent.getStringExtra("token") ?: TokenManager.token ?: ""
        existingMissionId = intent.getStringExtra("existingMissionId") ?: ""

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMissions)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val btnSoloMission = findViewById<Button>(R.id.btnSoloMission)
        //val tvMissionTitle = findViewById<TextView>(R.id.tvMissionTitle)

        fetchActiveMissions { missions ->
            runOnUiThread {
                recyclerView.adapter = MissionAdapter(missions) { mission ->
                    associateAndNavigate(mission.id, mission.title, mission.incidentType)
                }
                btnSoloMission.visibility = View.VISIBLE
                btnSoloMission.setOnClickListener { createSoloMission() }
            }
        }
    }

    private fun fetchActiveMissions(onResult: (List<MissionItem>) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/status/Active")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MissionActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val json = JSONArray(body)
                val list = mutableListOf<MissionItem>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    list.add(MissionItem(
                        obj.getString("missionId"),
                        obj.getString("title"),
                        obj.getString("location"),
                        obj.optString("incidentType", "")
                    ))
                }
                onResult(list)
            }
        })
    }

    private fun createSoloMission() {
        val client = OkHttpClient()

        LocationFinder(this).getCurrentLocationOnce { lat, lng ->
            val payload = JSONObject().apply {
                put("Title", firefighterName)
                put("Location", "Solo Mission")
                put("Latitude", lat)
                put("Longitude", lng)
                put("IncidentType", "Solo")
                put("CommanderId", userId)
            }.toString()

            val request = Request.Builder()
                .url("http://$ip:5081/api/Mission")
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this@MissionActivity, "Erro ao criar missão", Toast.LENGTH_LONG).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@MissionActivity, "Erro: $body", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    val json = JSONObject(body)
                    val newMissionId = json.getString("missionId")
                    val newTitle = json.getString("title")
                    associateAndNavigate(newMissionId, newTitle,"Solo")
                }
            })
        }
    }

    private fun associateAndNavigate(missionId: String, missionTitle: String, incidentType: String = "") {
        val client = OkHttpClient()

        val payload = JSONObject().apply {
            put("MissionID", missionId)
            put("FirefighterID", firefighterId)
        }.toString()

        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/associate")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                navigate(missionId, missionTitle, incidentType)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (response.code == 409) {
                    val message = try {
                        JSONObject(body).getString("message")
                    } catch (e: Exception) {
                        "Already assigned to another mission."
                    }
                    runOnUiThread {
                        Toast.makeText(this@MissionActivity, message, Toast.LENGTH_LONG).show()
                    }
                    return
                }
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@MissionActivity, "Erro (${response.code})", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                navigate(missionId, missionTitle, incidentType)
            }
        })
    }

    private fun navigate(missionId: String, missionTitle: String, incidentType: String = "") {
        runOnUiThread {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("firefighterId", firefighterId)
                putExtra("firefighterName", firefighterName)
                putExtra("missionId", missionId)
                putExtra("missionTitle", missionTitle)
                putExtra("role", role)
                putExtra("userId", userId)
                putExtra("incidentType", incidentType)
            }
            startActivity(intent)
            finish()
        }
    }
}

data class MissionItem(val id: String, val title: String, val location: String, val incidentType: String = "")