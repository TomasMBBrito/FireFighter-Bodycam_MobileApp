package com.example.bodycam

import android.content.Intent
import android.os.Build
import android.os.Bundle
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

    private val ip = "192.168.1.136"  //"10.25.36.11"

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        val firefighterId = intent.getStringExtra("firefighterId") ?: return
        val firefighterName = intent.getStringExtra("firefighterName") ?: ""

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMissions)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fetchMissions { missions ->
            runOnUiThread {
                recyclerView.adapter = MissionAdapter(missions) { mission ->
                    associateAndNavigate(firefighterId,firefighterName,mission);
                }
            }
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

    private fun associateAndNavigate(firefighterId: String, firefighterName: String, mission: MissionItem){
        val client = OkHttpClient()
        val payload = JSONObject().apply {
            put("MissionID",mission.id)
            put("FirefighterID",firefighterId)
        }.toString()

        var request = Request.Builder()
            .url("http://$ip:5081/associate")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM","Error associating : ${e.message}")
                navigate(firefighterId,firefighterName,mission)
            }

            override fun onResponse(call: Call, response: Response){
                //android.util.Log.d("BODYCAM", "Associate response: ${response.code}")
                navigate(firefighterId, firefighterName, mission)
            }
        })
    }

    private fun navigate(firefighterId: String, firefighterName: String, mission: MissionItem){
        runOnUiThread {
            val intent = Intent(this,MainActivity::class.java)
            intent.putExtra("firefighterId", firefighterId)
            intent.putExtra("firefighterName", firefighterName)
            intent.putExtra("missionId", mission.id)
            intent.putExtra("missionTitle", mission.title)
            startActivity(intent)
        }
    }
}

data class MissionItem(val id: String, val title: String, val location: String)