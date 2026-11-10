package com.pc5.android

import rikka.shizuku.Shizuku
import java.lang.reflect.InvocationTargetException

data class ShellResult(val code: Int, val out: String, val mode: String)

object Injector {

    private const val BASE = "/sdcard/Android/data"

    /* "shizuku", "app", or "unknown" — how the last process was actually spawned. */
    @Volatile private var mode = "unknown"

    private val pkgRe = Regex("^[A-Za-z0-9._]+$")

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0
    } catch (e: Throwable) {
        false
    }

    /* Reflection, but never silent: if both Shizuku entry points fail we fall back to the
       app uid and say so through the mode field, because that path needs storage rights. */
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

    /* stderr is folded into stdout with 2>&1 so a single pipe read can never stall. */
    fun sh(cmd: String): ShellResult {
        return try {
            val p = newProcess(arrayOf("sh", "-c", "$cmd 2>&1"))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            ShellResult(code, out.trim(), mode)
        } catch (e: Throwable) {
            ShellResult(-1, e.message ?: e.javaClass.simpleName, mode)
        }
    }

    /* "0" for root, "2000" for the adb shell user, the app's own uid otherwise. */
    fun uid(): String {
        val u = sh("id -u").out
        return u.ifBlank { "?" }
    }

    fun privileged(): Boolean {
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

            /* -n preserves the original when the name carried the "original-absent" marker. */
            val cp = if (item.skipIfExists) "cp -n" else "cp -f"
            val r = sh("$cp '${item.file.absolutePath}' '$dest'")
            if (r.code != 0) return r

            /* Cosmetic on emulated storage, non-fatal where chmod refuses. */
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
            if (sh("test -s '$dir/$rel'").code != 0) missing.add(rel)
        }
        return missing
    }

    fun wipe(stage: Stage, target: Target): ShellResult {
        if (stage.items.isEmpty()) return ShellResult(0, "", mode)
        val dir = destOf(target)

        val list = stage.items.joinToString(" ") {
            "'$dir/${it.file.relativeTo(stage.root).path}'"
        }
        val r = sh("rm -f $list")

        /* Prune directories the payload created, deepest last. */
        stage.items
            .map { "$dir/${it.file.relativeTo(stage.root).path}".substringBeforeLast('/', "") }
            .filter { it.isNotEmpty() && it != dir }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { sh("rmdir '$it'") }

        return r
    }
}
