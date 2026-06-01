package com.example.bodycam

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
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

    private val ip = "100.102.144.13"
    private lateinit var locationFinder: LocationFinder

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        val firefighterId = intent.getStringExtra("firefighterId")

        if (firefighterId.isNullOrEmpty()) {
            Toast.makeText(this, "Firefighter ID missing!", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val firefighterName = intent.getStringExtra("firefighterName") ?: ""
        val userId = intent.getStringExtra("userId") ?: return
        val role = intent.getStringExtra("role") ?: "Firefighter"

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMissions)
        recyclerView.layoutManager = LinearLayoutManager(this)

        locationFinder = LocationFinder(this)
        locationFinder.start()

        fetchMissions { missions ->
            runOnUiThread {
                recyclerView.adapter = MissionAdapter(missions) { mission ->
                    associateAndNavigate(firefighterId,firefighterName,mission,role,userId);
                }
            }
        }

        val btnSoloMission = findViewById<Button>(R.id.btnSoloMission)
        btnSoloMission.setOnClickListener {
            createSoloMission(firefighterId,userId, firefighterName,role)
        }
    }

    private fun fetchMissions(onResult: (List<MissionItem>) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission")
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
                        obj.getString("location")
                    ))
                }
                onResult(list)
            }
        })
    }

    private fun createSoloMission(firefighterId:String, userId: String, firefighterName: String, role : String) {
        val client = OkHttpClient()

        // Usa as coordenadas atuais do GPS
        val lat = locationFinder.currentLat
        val lng = locationFinder.currentLng

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
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM", "Erro criar missão: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MissionActivity, "Erro ao criar missão", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                //android.util.Log.d("BODYCAM", "Missão criada: $body")

                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@MissionActivity, "Erro: $body", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val json = JSONObject(body)
                val mission = MissionItem(
                    json.getString("missionId"),
                    json.getString("title"),
                    json.getString("location")
                )
                // Associa e navega automaticamente
                associateAndNavigate(firefighterId, firefighterName, mission,role,userId)
            }
        })
    }

    private fun associateAndNavigate(firefighterId: String, firefighterName: String, mission: MissionItem,role : String, userId : String){
        val client = OkHttpClient()
        val payload = JSONObject().apply {
            put("MissionID",mission.id)
            put("FirefighterID",firefighterId)
        }.toString()

        android.util.Log.d("BODYCAM", "firefighterId=$firefighterId")
        android.util.Log.d("BODYCAM", "missionId=${mission.id}")

        var request = Request.Builder()
            .url("http://$ip:5081/associate")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM","Error associating : ${e.message}")
                navigate(firefighterId,firefighterName,mission,role,userId)
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
                        Toast.makeText(
                            this@MissionActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    return
                }

                if (!response.isSuccessful) {

                    runOnUiThread {
                        Toast.makeText(
                            this@MissionActivity,
                            "Error (${response.code})",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    return
                }

                navigate(
                    firefighterId,
                    firefighterName,
                    mission,
                    role,
                    userId
                )
            }
        })
    }

    private fun navigate(firefighterId: String, firefighterName: String, mission: MissionItem, role : String, userId : String){
        runOnUiThread {
            val intent = Intent(this,MainActivity::class.java)
            intent.putExtra("firefighterId", firefighterId)
            intent.putExtra("firefighterName", firefighterName)
            intent.putExtra("missionId", mission.id)
            intent.putExtra("missionTitle", mission.title)
            intent.putExtra("role", role)
            intent.putExtra("userId", userId)
            startActivity(intent)
        }
    }
}

data class MissionItem(val id: String, val title: String, val location: String)