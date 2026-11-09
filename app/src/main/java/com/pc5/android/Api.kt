package com.pc5.android

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Api {

    private val http = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    fun checkKey(key: String, did: String): JSONObject? = try {
        val base = Native.url()
        val req = Request.Builder()
            .url("$base/api/check-key?key=$key&did=$did")
            .header("User-Agent", "PC5-ANDROID")
            .build()
        http.newCall(req).execute().use { r -> r.body?.string()?.let { JSONObject(it) } }
    } catch (e: Exception) { null }
}
