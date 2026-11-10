package com.pc5.android

import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.InvocationTargetException

data class ShellResult(val code: Int, val out: String, val mode: String)

object Injector {

    private const val BASE = "/sdcard/Android/data"

    /* "root", "shell" or the app's own uid — decided by what newProcess() managed to do. */
    @Volatile private var mode = "unknown"

    private val pkgRe = Regex("^[A-Za-z0-9._]+$")

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    } catch (e: Throwable) {
        false
    }

    /* Reflection, but never silent: if both Shizuku entry points fail we fall back to the
       app uid and *say so* through the mode field, because that path needs storage rights. */
    private fun newProcess(cmd: Array<String>): Process {
        val types = arrayOf<Class<*>>(
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        val failures = StringBuilder()

        for (className in arrayOf("rikka.shizuku.Shizuku", "rikka.shizuku.Shell")) {
            try {
                val m = Class.forName(className).getDeclaredMethod("newProcess", *types)
                m.isAccessible = true
                val p = m.invoke(null, cmd, null, null)
                if (p is Process) {
                    mode = "shizuku"
                    return p
                }
            } catch (e: Throwable) {
                val c = (e as? InvocationTargetException)?.targetException ?: e
                failures.append(className).append(": ").append(c.javaClass.simpleName).append("; ")
            }
        }

        mode = "app"
        return try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            throw IllegalStateException("no usable shell ($failures) ${e.message}")
        }
    }

    fun sh(cmd: String): ShellResult = try {
        val p = newProcess(arrayOf("sh", "-c", "$cmd 2>&1"))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor() to out.trim()
            .let { ShellResult(it.first, it.second, mode) }
    } catch (e: Throwable) {
        ShellResult(-1, e.message ?: e.javaClass.simpleName, mode)
    }

    /* Returns "0" for root, "2000" for the adb shell user, the app uid otherwise. */
    fun uid(): String = sh("id -u").out.ifBlank { "?" }

    fun canWriteExternal(): Boolean {
        val u = uid()
        return u == "0" || u == "2000"
    }

    private fun destOf(t: Target): String {
        require(pkgRe.matches(t.pkg)) { "bad package name '${t.pkg}'" }
        return "$BASE/${t.pkg}/${t.dest}"
    }

    fun push(stage: Stage, target: Target): ShellResult {
        val dir = destOf(target)

        val mk = sh("mkdir -p '$dir'")
        if (mk.code != 0) return mk

        for (item in stage.items) {
            val rel = item.file.relativeTo(stage.root).path
            val dest = "$dir/$rel"

            val parent = dest.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && parent != dir) {
                val p = sh("mkdir -p '$parent'")
                if (p.code != 0) return p
            }

            /* -n preserves the original when the name carries the "original-absent" marker. */
            val cp = if (item.skipIfExists) "cp -n" else "cp -f"
            val r = sh("$cp '${item.file.absolutePath}' '$dest'")
            if (r.code != 0) return r

            /* Cosmetic on emulated storage and non-fatal on ROMs where chmod refuses. */
            sh("chmod 666 '$dest'")
        }
        return ShellResult(0, "", mode)
    }

    /** Relative paths that did not survive the copy. */
    fun verify(stage: Stage, target: Target): List<String> {
        val dir = destOf(target)
        val missing = ArrayList<String>()
        for (item in stage.items) {
            val rel = item.file.relativeTo(stage.root).path
            val r = sh("test -s '$dir/$rel'")
            if (r.code != 0) missing.add(rel)
        }
        return missing
    }

    fun wipe(stage: Stage, target: Target): ShellResult {
        val dir = destOf(target)
        if (stage.items.isEmpty()) return ShellResult(0, "", mode)

        val list = stage.items.joinToString(" ") { "'$dir/${it.file.relativeTo(stage.root).path}'" }
        val r = sh("rm -f $list")

        for (item in stage.items) {
            val parent = "$dir/${item.file.relativeTo(stage.root).path}".substringBeforeLast('/', "")
            if (parent.isNotEmpty() && parent != dir) sh("rmdir '$parent'")
        }
        return r
    }
}
