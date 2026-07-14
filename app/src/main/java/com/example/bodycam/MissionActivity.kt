package com.example.bodycam

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONArray
import java.io.IOException

class MissionActivity : AppCompatActivity() {

    private val ip = "100.126.183.52"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mission)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMissions)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fetchMissions { missions ->
            runOnUiThread {
                recyclerView.adapter = MissionAdapter(missions) { mission ->
                    // Missão normal — vai para FirefighterActivity com missionId
                    val intent = Intent(this, FirefighterActivity::class.java).apply {
                        putExtra("missionId", mission.id)
                        putExtra("missionTitle", mission.title)
                        putExtra("isSolo", false)
                    }
                    startActivity(intent)
                }
            }
        }

        val btnSoloMission = findViewById<Button>(R.id.btnSoloMission)
        btnSoloMission.setOnClickListener {
            // Missão solo — vai para FirefighterActivity sem missionId
            val intent = Intent(this, FirefighterActivity::class.java).apply {
                putExtra("isSolo", true)
            }
            startActivity(intent)
        }
    }

    private fun fetchMissions(onResult: (List<MissionItem>) -> Unit) {
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
                        obj.getString("location")
                    ))
                }
                onResult(list)
            }
        })
    }
}

data class MissionItem(val id: String, val title: String, val location: String)