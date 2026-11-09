package com.pc5.android

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Payload {

    private const val ASSET = "raw1/p.bin"

    /* Same derivation sealRaw1() uses — one source of truth via BuildConfig. */
    private fun aesKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(BuildConfig.RAW1_KEY.toByteArray())
        return SecretKeySpec(digest, "AES")
    }

    private fun open(b: ByteArray): ByteArray {
        if (b.size <= 12) throw IllegalStateException("sealed block too short")
        val iv = b.copyOfRange(0, 12)
        val body = b.copyOfRange(12, b.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
        return c.doFinal(body)
    }

    /* Restores every sealed file under cacheDir/pc5stage and returns them. */
    fun stage(ctx: Context): List<File> {
        val root = File(ctx.cacheDir, "pc5stage")
        root.deleteRecursively()
        root.mkdirs()

        val sealed = ctx.assets.open(ASSET).use { it.readBytes() }
        val map = JSONObject(String(open(sealed), Charsets.UTF_8))

        val out = ArrayList<File>()
        val names = map.keys().asSequence().toList().sorted()

        for (name in names) {
            val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.any { it == ".." }) continue

            var dir = root
            for (i in 0 until parts.size - 1) dir = File(dir, parts[i])
            dir.mkdirs()

            val target = File(dir, parts.last())
            val plain = open(Base64.decode(map.getString(name), Base64.DEFAULT))
            target.writeBytes(plain)
            out.add(target)
        }
        return out
    }
}
