package com.pc5.android

import rikka.shizuku.Shizuku
import java.io.File

object Injector {

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    } catch (e: Throwable) { false }

    fun sh(cmd: String): Pair<Int, String> {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to out.trim()
    }

    fun push(files: List<File>, target: Target): Pair<Int, String> {
        val dir = "/sdcard/Android/data/${target.pkg}/${target.dest}"
        sh("mkdir -p '$dir'").let { if (it.first != 0) return it }
        var last = 0 to ""
        for (f in files) {
            val sub = f.relativeTo(f.parentFile!!).path
            last = sh("cp -f '${f.absolutePath}' '$dir/$sub' && chmod 666 '$dir/$sub'")
            if (last.first != 0) return last
        }
        return last
    }

    fun wipe(files: List<File>, target: Target): Pair<Int, String> {
        val dir = "/sdcard/Android/data/${target.pkg}/${target.dest}"
        val list = files.joinToString(" ") { "'$dir/${it.name}'" }
        return sh("rm -f $list")
    }
}
