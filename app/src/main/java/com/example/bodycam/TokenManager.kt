package com.example.bodycam

object TokenManager {
    var token: String? = null

    fun authHeader(): String? = token?.let { "Bearer $it" }
}