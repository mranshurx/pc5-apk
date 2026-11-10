package com.pc5.android

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One restored payload entry. [skipIfExists] comes from the .network-original-absent suffix. */
class Item(val file: File, val skipIfExists: Boolean)

/** Whole staged tree. Paths are always resolved relative to [root]. */
class Stage(val root: File, val items: List<Item>) {
    val empty: Boolean get() = items.isEmpty()
    val count: Int get() = items.size
}

object Payload {

    private const val ASSET = "raw1/p.bin"

    private fun aesKey(): SecretKeySpec =
        SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(BuildConfig.RAW1_KEY.toByteArray()),
            "AES"
        )

    private fun open(b: ByteArray): ByteArray {
        require(b.size > 12) { "sealed block too short (${b.size} bytes)" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        return c.doFinal(b.copyOfRange(12, b.size))
    }

    /* Shizuku's shell (uid 2000) must be able to traverse and read the staged tree,
       so the app-data dir, the cache dir, the stage root and every file are opened up. */
    private fun expose(f: File, dir: Boolean) {
        f.setReadable(true, false)
        if (dir) f.setExecutable(true, false)
    }

    private fun validPath(name: String): List<String>? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        if (parts.any { it == "." || it == ".." }) return null
        return parts
    }

    fun stage(ctx: Context): Stage {
        val root = File(ctx.cacheDir, "pc5stage")
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) error("cannot create stage dir ${root.absolutePath}")

        ctx.filesDir.parentFile?.let { expose(it, true) }
        expose(ctx.cacheDir, true)
        expose(root, true)

        val sealed = try {
            ctx.assets.open(ASSET).use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "payload missing at assets/$ASSET — raw1/ had no files when this APK was built", e
            )
        }

        val map = try {
            JSONObject(String(open(sealed), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("payload cannot be decrypted — RAW1_KEY mismatch between build and runtime", e)
        }

        val out = ArrayList<Item>()
        for (name in map.keys().asSequence().sorted()) {
            val parts = validPath(name) ?: continue

            var dir = root
            for (i in 0 until parts.size - 1) {
                dir = File(dir, parts[i])
                dir.mkdirs()
                expose(dir, true)
            }

            val target = File(dir, parts.last())

            val value = map.get(name)
            val (b64, skip) = if (value is JSONObject) {
                value.getString("d") to value.optBoolean("o", false)
            } else {
                value.toString() to false
            }

            target.writeBytes(open(Base64.decode(b64, Base64.DEFAULT)))
            expose(target, false)
            out.add(Item(target, skip))
        }

        if (out.isEmpty()) throw IllegalStateException("payload decrypted to zero files")
        return Stage(root, out)
    }

    fun discard(stage: Stage) {
        runCatching { stage.root.deleteRecursively() }
    }
}
