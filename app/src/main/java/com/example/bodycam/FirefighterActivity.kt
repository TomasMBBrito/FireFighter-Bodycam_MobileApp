package com.example.bodycam

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
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
import kotlin.apply

class FirefighterActivity : AppCompatActivity() {

    private val ip = "192.168.1.136"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firefighter)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerFirefighters)
        recyclerView.layoutManager = LinearLayoutManager(this)

        fetchFirefighters { firefighters ->
            runOnUiThread {
                recyclerView.adapter = FirefighterAdapter(firefighters) { firefighter ->
                    showPasswordDialog(firefighter)
                }
            }
        }
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
            .setMessage("Introduza a password para continuar.")
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
            put("userId", firefighter.userId)
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
                    Toast.makeText(
                        this@FirefighterActivity,
                        "Erro de ligação: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    when (response.code) {
                        200 -> {
                            // Login Sucessful
                            val intent = Intent(this@FirefighterActivity, MissionActivity::class.java).apply {
                                putExtra("firefighterId", firefighter.id)
                                putExtra("firefighterName", firefighter.name)
                                putExtra("userId", firefighter.userId)
                                putExtra("role", firefighter.role)
                            }
                            startActivity(intent)
                        }
                        401 -> Toast.makeText(
                            this@FirefighterActivity,
                            "Password incorreta. Tente novamente.",
                            Toast.LENGTH_SHORT
                        ).show()
                        404 -> Toast.makeText(
                            this@FirefighterActivity,
                            "Utilizador não encontrado.",
                            Toast.LENGTH_SHORT
                        ).show()
                        else -> Toast.makeText(
                            this@FirefighterActivity,
                            "Erro inesperado (${response.code}).",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
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