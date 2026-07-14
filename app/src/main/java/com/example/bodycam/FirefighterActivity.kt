package com.example.bodycam

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
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

class FirefighterActivity : AppCompatActivity() {

    private val ip = "100.126.183.52"
    private var missionId: String = ""
    private var missionTitle: String = ""
    private var isSolo: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firefighter)

        missionId = intent.getStringExtra("missionId") ?: ""
        missionTitle = intent.getStringExtra("missionTitle") ?: ""
        isSolo = intent.getBooleanExtra("isSolo", false)

        findViewById<TextView>(R.id.tvMissionTitle).text =
            if (isSolo) "Missão Solo" else missionTitle

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerFirefighters)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fetchAllFirefighters { firefighters ->
            runOnUiThread {
               recyclerView.adapter = FirefighterAdapter(firefighters) { firefighter ->
                    showPasswordDialog(firefighter)
               }
            }
        }
    }

    private fun fetchAllFirefighters(onResult: (List<FirefighterItem>) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://$ip:5081/api/User/firefighters")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
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
                        id = obj.getString("firefighterId"),
                        userId = obj.getString("userId"),
                        name = obj.getString("name"),
                        role = obj.getString("role"),
                        username = obj.getString("username"),
                        station = obj.optString("station", "Unknown"),
                        online = obj.optBoolean("online", false)
                    ))
                }
                onResult(list)
            }
        })
    }

    private fun showPasswordDialog(firefighter: FirefighterItem) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Introduza a sua password"
        }

        val container = android.widget.FrameLayout(this).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Login — ${firefighter.name}")
            .setMessage(firefighter.station)
            .setView(container)
            .setPositiveButton("Entrar") { dialog, _ ->
                val password = input.text.toString().trim()
                if (password.isEmpty()) {
                    Toast.makeText(this, "A password não pode estar vazia.", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    validateLogin(firefighter, password)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun validateLogin(firefighter: FirefighterItem, password: String) {
        val client = OkHttpClient()

        val json = JSONObject().apply {
            put("username",  firefighter.username)
            put("password", password)
        }

        android.util.Log.d("LOGIN_DEBUG", "Enviando: ${json}")

        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("http://$ip:5081/api/User/login")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@FirefighterActivity, "Erro de ligação: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val resBody = response.body?.string() ?: ""
                android.util.Log.d("LOGIN_DEBUG", "Status: ${response.code}, Body: $resBody")
                runOnUiThread {
                    when (response.code) {
                        200 -> {
                            val resJson = JSONObject(resBody)
                            val userId = resJson.getString("userId")
                            val token = resJson.getString("token")
                            TokenManager.token = token
                            //setOnlineStatus(userId, true)
                            if (isSolo) {
                                createSoloMission(firefighter, userId, token)
                            } else {
                                associateAndNavigate(firefighter, userId, token)
                            }
                        }
                        401 -> Toast.makeText(this@FirefighterActivity, "Password incorreta.", Toast.LENGTH_SHORT).show()
                        404 -> Toast.makeText(this@FirefighterActivity, "Utilizador não encontrado.", Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(this@FirefighterActivity, "Erro (${response.code}).", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun createSoloMission(firefighter: FirefighterItem, userId: String, token: String) {
        val client = OkHttpClient()

        LocationFinder(this).getCurrentLocationOnce { lat, lng ->
            val payload = JSONObject().apply {
                put("Title", firefighter.name)
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
                        Toast.makeText(this@FirefighterActivity, "Erro ao criar missão", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@FirefighterActivity, "Erro: $body", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    val json = JSONObject(body)
                    missionId = json.getString("missionId")
                    missionTitle = json.getString("title")
                    associateAndNavigate(firefighter, userId, token)
                }
            })
        }
    }

    private fun associateAndNavigate(firefighter: FirefighterItem, userId: String, token: String) {
        val client = OkHttpClient()

        val payload = JSONObject().apply {
            put("MissionID", missionId)
            put("FirefighterID", firefighter.id)
        }.toString()

        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/associate")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                navigate(firefighter, userId)
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
                        Toast.makeText(this@FirefighterActivity, message, Toast.LENGTH_LONG).show()
                    }
                    return
                }
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@FirefighterActivity, "Erro (${response.code})", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                navigate(firefighter, userId)
            }
        })
    }

    private fun navigate(firefighter: FirefighterItem, userId: String) {
        runOnUiThread {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("firefighterId", firefighter.id)
                putExtra("firefighterName", firefighter.name)
                putExtra("missionId", missionId)
                putExtra("missionTitle", missionTitle)
                putExtra("role", firefighter.role)
                putExtra("userId", userId)
            }
            startActivity(intent)
        }
    }
}

data class FirefighterItem(
    val id: String,
    val userId: String,
    val name: String,
    val role: String,
    val username: String,
    val station: String = "Unknown",
    val online: Boolean = false
)