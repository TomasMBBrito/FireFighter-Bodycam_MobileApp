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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firefighter)

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
            put("username", firefighter.username)
            put("password", password)
        }

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
                runOnUiThread {
                    when (response.code) {
                        200 -> {
                            val resJson = JSONObject(resBody)
                            val userId = resJson.getString("userId")
                            val token = resJson.getString("token")
                            TokenManager.token = token
                            checkActiveMission(firefighter, userId, token)
                        }
                        401 -> Toast.makeText(this@FirefighterActivity, "Password incorreta.", Toast.LENGTH_SHORT).show()
                        404 -> Toast.makeText(this@FirefighterActivity, "Utilizador não encontrado.", Toast.LENGTH_SHORT).show()
                        else -> Toast.makeText(this@FirefighterActivity, "Erro (${response.code}).", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun checkActiveMission(firefighter: FirefighterItem, userId: String, token: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://$ip:5081/api/Mission/firefighter/${firefighter.id}/active-mission")
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // sem rede: assume sem missão, deixa o utilizador escolher/criar
                goToMissionActivity(firefighter, userId, token, "")
            }

            override fun onResponse(call: Call, response: Response) {
                val existingMissionId = if (response.code == 200) {
                    val body = response.body?.string() ?: "{}"
                    JSONObject(body).optString("missionId", "")
                } else {
                    "" // 404 -> não tem missão ativa
                }
                goToMissionActivity(firefighter, userId, token, existingMissionId)
            }
        })
    }

    private fun goToMissionActivity(
        firefighter: FirefighterItem,
        userId: String,
        token: String,
        existingMissionId: String
    ) {
        runOnUiThread {
            val intent = Intent(this, MissionActivity::class.java).apply {
                putExtra("firefighterId", firefighter.id)
                putExtra("firefighterName", firefighter.name)
                putExtra("role", firefighter.role)
                putExtra("userId", userId)
                putExtra("token", token)
                putExtra("existingMissionId", existingMissionId)
            }
            startActivity(intent)
            finish()
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