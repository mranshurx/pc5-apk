package com.pc5.android

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

object Hwid {

    private const val PREFS = "pc5_id"
    private const val K = "did"

    fun of(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(K, null)?.let { return it }

        val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
        val id = "D" + sha256(raw + UUID.randomUUID()).take(16)
        sp.edit().putString(K, id).apply()
        return id
    }

    fun sha256(s: String): String = sha256(s.toByteArray())

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
