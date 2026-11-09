package com.pc5.android

import rikka.shizuku.Shell
import rikka.shizuku.Shizuku
import java.io.File

object Injector {

    fun shizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
        } catch (e: Throwable) {
            false
        }
    }

    fun sh(cmd: String): Pair<Int, String> {
        return try {
            val p: Process = Shell.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out: String = p.inputStream.bufferedReader().readText() +
                p.errorStream.bufferedReader().readText()
            val code: Int = p.waitFor()
            code to out.trim()
        } catch (e: Throwable) {
            -1 to (e.message ?: "shell error")
        }
    }

    fun push(files: List<File>, target: Target): Pair<Int, String> {
        val dir = "/sdcard/Android/data/" + target.pkg + "/" + target.dest
        val mk = sh("mkdir -p '" + dir + "'")
        if (mk.first != 0) return mk

        var last: Pair<Int, String> = 0 to ""
        for (f in files) {
            val parent: File? = f.parentFile
            val sub: String = if (parent == null) f.name else f.relativeTo(parent).path
            last = sh("cp -f '" + f.absolutePath + "' '" + dir + "/" + sub + "' && chmod 666 '" + dir + "/" + sub + "'")
            if (last.first != 0) return last
        }
        return last
    }

    fun wipe(files: List<File>, target: Target): Pair<Int, String> {
        val dir = "/sdcard/Android/data/" + target.pkg + "/" + target.dest
        val list = files.joinToString(" ") { "'" + dir + "/" + it.name + "'" }
        return sh("rm -f " + list)
    }
}
