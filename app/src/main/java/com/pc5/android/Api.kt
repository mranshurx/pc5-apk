package com.pc5.android

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Api {

    private const val FALLBACK = "https://pc5-android.onrender.com"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun base(): String {
        val b = try { Native.url().trim() } catch (e: Throwable) { "" }
        val u = if (b.isEmpty()) FALLBACK else b
        return u.trimEnd('/')
    }

    fun checkKey(key: String, did: String): JSONObject? {
        val url = "${base()}/api/check-key?key=$key&did=$did"
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PC5-ANDROID")
                    .build()
                http.newCall(req).execute().use { r ->
                    val body = r.body?.string() ?: return null
                    return JSONObject(body)
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                try { Thread.sleep(1500) } catch (i: InterruptedException) { return null }
            }
        }
        return null
    }
}
