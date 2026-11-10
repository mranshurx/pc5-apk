package com.pc5.android

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray

data class Target(val label: String, val pkg: String, val dest: String) {
    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }
}

object Targets {
    fun load(ctx: Context): List<Target> = try {
        val arr = JSONArray(ctx.assets.open("targets.json").bufferedReader().readText())
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val pkg = o.optString("pkg").trim()
            if (pkg.isEmpty()) return@mapNotNull null
            Target(
                o.optString("label", pkg),
                pkg,
                o.optString("dest", "files").trim().trim('/').ifBlank { "files" }
            )
        }
    } catch (e: Exception) { emptyList() }
}
