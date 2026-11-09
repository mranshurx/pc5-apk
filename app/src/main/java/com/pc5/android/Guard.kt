package com.pc5.android

import android.content.Context
import android.content.pm.PackageManager

object Guard {

    fun certSha256(ctx: Context): String = try {
        val sig = ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo.apkContentsSigners.first().toByteArray()
        Hwid.sha256(sig)
    } catch (e: Exception) { "" }

    fun tampered(ctx: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        val expected = try { Native.cert() } catch (e: Throwable) { "" }
        if (expected.isBlank()) return false
        return !certSha256(ctx).equals(expected, ignoreCase = true)
    }

    fun debugged(): Boolean = try { Native.traced() } catch (e: Throwable) { false }
}
