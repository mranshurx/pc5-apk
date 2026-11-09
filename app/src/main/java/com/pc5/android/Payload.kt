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

    /* Shizuku's shell (uid 2000) must be able to reach and read the staged tree,
       so the app-data dir, the cache dir and every staged file are opened up. */
    private fun expose(f: File, dir: Boolean) {
        f.setReadable(true, false)
        if (dir) f.setExecutable(true, false)
    }

    fun stage(ctx: Context): List<File> {
        ctx.filesDir.parentFile?.let { expose(it, true) }
        expose(ctx.cacheDir, true)

        val root = File(ctx.cacheDir, "pc5stage")
        root.deleteRecursively()
        root.mkdirs()
        expose(root, true)

        val sealed = ctx.assets.open(ASSET).use { it.readBytes() }
        val map = JSONObject(String(open(sealed), Charsets.UTF_8))

        val out = ArrayList<File>()
        val names = map.keys().asSequence().toList().sorted()

        for (name in names) {
            val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.any { it == ".." }) continue

            var dir = root
            for (i in 0 until parts.size - 1) {
                dir = File(dir, parts[i])
                dir.mkdirs()
                expose(dir, true)
            }

            val target = File(dir, parts.last())
            val plain = open(Base64.decode(map.getString(name), Base64.DEFAULT))
            target.writeBytes(plain)
            expose(target, false)
            out.add(target)
        }
        return out
    }
}
