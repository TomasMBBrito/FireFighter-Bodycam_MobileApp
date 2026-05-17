package com.example.bodycam

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONArray
import java.io.IOException

class FirefighterActivity : AppCompatActivity() {

    private val ip = "192.168.1.77" // "172.20.10.12"  //"10.25.36.11"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firefighter)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerFirefighters)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fetchFirefighters { firefighters ->
            runOnUiThread {
                recyclerView.adapter = FirefighterAdapter(firefighters) { firefighter ->
                    val intent = Intent(this, MissionActivity::class.java)
                    intent.putExtra("firefighterId", firefighter.id)
                    intent.putExtra("firefighterName", firefighter.name)
                    intent.putExtra("userId", firefighter.userId)
                    intent.putExtra("role", firefighter.role)
                    startActivity(intent)
                }
            }
        }
    }

    private fun fetchFirefighters(onResult: (List<FirefighterItem>) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://$ip:5081/api/User/firefighters")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("BODYCAM", "Erro fetch firefighters: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@FirefighterActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                val json = JSONArray(body)
                val list = mutableListOf<FirefighterItem>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    list.add(FirefighterItem(
                        id = if (obj.has("firefighterId") && !obj.isNull("firefighterId"))
                            obj.getString("firefighterId") else "",
                        userId = obj.getString("userId"),
                        name = obj.getString("name"),
                        role = obj.getString("role")
                    ))
                }
                onResult(list)
            }
        })
    }
}

data class FirefighterItem(val id: String, val userId: String, val name: String, val role : String)