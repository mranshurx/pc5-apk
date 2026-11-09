package com.pc5.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ = 4711
        private const val DELETE_AFTER_MS = 3000L
    }

    private lateinit var unlockView: View
    private lateinit var dashView: View
    private lateinit var keyInput: EditText
    private lateinit var errorText: TextView
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var spinner: Spinner
    private lateinit var leftText: TextView
    private lateinit var keyLabel: TextView
    private lateinit var deviceLabel: TextView
    private lateinit var expiryLabel: TextView

    private var targets: List<Target> = emptyList()
    private var activeKey = ""
    private var did = ""
    private var timer: CountDownTimer? = null
    private var pending: Pair<List<File>, Target>? = null

    /* Set when a Start tap is waiting on the permission dialog. */
    private var awaitingStart = false
    private var busy = false

    private val permListener = Shizuku.OnRequestPermissionResultListener { id, res ->
        if (id != REQ) return@OnRequestPermissionResultListener
        if (res == 0) {
            log("Shizuku granted")
            if (awaitingStart) {
                awaitingStart = false
                doStart()
            }
        } else {
            log("Shizuku permission denied — tap Start to ask again")
            awaitingStart = false
            startBtn.isEnabled = true
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)

        unlockView = findViewById(R.id.unlockView)
        dashView = findViewById(R.id.dashView)
        keyInput = findViewById(R.id.keyInput)
        errorText = findViewById(R.id.errorText)
        statusText = findViewById(R.id.statusText)
        startBtn = findViewById(R.id.startBtn)
        spinner = findViewById(R.id.versionSpinner)
        leftText = findViewById(R.id.leftText)
        keyLabel = findViewById(R.id.keyLabel)
        deviceLabel = findViewById(R.id.deviceLabel)
        expiryLabel = findViewById(R.id.expiryLabel)

        did = Hwid.of(this)
        Shizuku.addRequestPermissionResultListener(permListener)

        if (Guard.tampered(this)) log("signature mismatch — repackaged build")
        if (Guard.debugged()) log("debugger attached")

        findViewById<Button>(R.id.pasteBtn).setOnClickListener { paste() }
        findViewById<Button>(R.id.unlockBtn).setOnClickListener { unlock() }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener { logout() }
        startBtn.setOnClickListener { start() }

        targets = Targets.load(this)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            targets.map { t -> t.label + if (t.installed(this)) "" else "  ·  not installed" }
        )

        val saved = getSharedPreferences("pc5", MODE_PRIVATE).getString("key", null)
        if (saved != null) revalidate(saved) else showUnlock()

        /* Ask up front, on first launch, so Start never has to. */
        statusText.postDelayed({ askShizukuOnLaunch() }, 600)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        timer?.cancel()
        if (pending != null) lifecycleScope.launch { runCatching { wipeNow() } }
        super.onDestroy()
    }

    /* ---------------- auth ---------------- */

    private fun normalize(v: String): String {
        var s = v.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (s.startsWith("PC5")) s = s.substring(3)
        return if (s.isEmpty()) "" else "PC5-" + s.take(6)
    }

    private fun paste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.let { keyInput.setText(normalize(it.toString())) }
    }

    private fun unlock() {
        val key = normalize(keyInput.text.toString())
        keyInput.setText(key)
        errorText.text = ""
        if (key.isEmpty()) { errorText.text = "Enter your access key."; return }

        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { Api.checkKey(key, did) }
            when {
                d == null -> errorText.text = "Connection error. Check server status."
                d.optBoolean("maintenance") -> errorText.text = d.optString("message", "Under maintenance.")
                d.optBoolean("valid") -> enter(d)
                else -> errorText.text = d.optString("message", "Invalid key.")
            }
        }
    }

    private fun revalidate(key: String) {
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { Api.checkKey(key, did) }
            if (d != null && d.optBoolean("valid")) enter(d) else showUnlock()
        }
    }

    private fun enter(d: JSONObject) {
        activeKey = d.optString("key", activeKey)
        getSharedPreferences("pc5", MODE_PRIVATE).edit().putString("key", activeKey).apply()

        keyLabel.text = activeKey.take(4) + "•••" + activeKey.takeLast(2)
        deviceLabel.text =
            if (d.optString("deviceLimit") == "single") "1 device · ${did.takeLast(4)}" else "Unlimited"
        expiryLabel.text = d.optString("expiresAt", "—").replace("T", " ").take(19)

        val ms = d.optLong("remainingMs", 0L)
        timer?.cancel()
        timer = object : CountDownTimer(ms.coerceAtLeast(1000L), 1000) {
            override fun onTick(l: Long) { leftText.text = fmt(l) }
            override fun onFinish() { leftText.text = "EXPIRED"; showUnlock() }
        }.start()

        unlockView.visibility = View.GONE
        dashView.visibility = View.VISIBLE
        log("key accepted")
    }

    private fun fmt(ms: Long): String {
        val d = ms / 86400000
        val h = (ms % 86400000) / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        return (if (d > 0) "${d}d " else "") + "${h}h ${m}m ${s}s"
    }

    private fun logout() {
        timer?.cancel()
        getSharedPreferences("pc5", MODE_PRIVATE).edit().remove("key").apply()
        activeKey = ""
        keyInput.setText("")
        showUnlock()
    }

    private fun showUnlock() {
        dashView.visibility = View.GONE
        unlockView.visibility = View.VISIBLE
    }

    /* ---------------- shizuku ---------------- */

    private fun shizukuUsable(): Boolean = try {
        Injector.shizukuReady() && Shizuku.pingBinder() &&
            (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == 0)
    } catch (e: Throwable) { false }

    private fun installed(p: String) = try {
        packageManager.getPackageInfo(p, 0); true
    } catch (e: Exception) { false }

    /* Fires once on launch: prompts straight away if Shizuku is up and unpermitted. */
    private fun askShizukuOnLaunch() {
        try {
            if (!installed("moe.shizuku.privileged.api")) {
                log("Shizuku not installed — install it to inject")
                return
            }
            if (!Shizuku.pingBinder()) {
                log("Shizuku is not running — start Shizuku first")
                return
            }
            if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == 0) {
                log("Shizuku ready")
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                log("Shizuku permission denied permanently — enable it inside Shizuku")
                return
            }
            log("requesting Shizuku permission")
            Shizuku.requestPermission(REQ)
        } catch (e: Throwable) {
            log("Shizuku error: ${e.message}")
        }
    }

    /* Returns true when we can proceed right now; otherwise asks and parks the tap. */
    private fun ensureShizuku(): Boolean {
        if (shizukuUsable()) return true
        try {
            if (!Shizuku.pingBinder()) {
                if (installed("moe.shizuku.privileged.api")) {
                    log("Shizuku is not running — start it, then tap Start again")
                    startActivity(Intent(Intent.ACTION_VIEW).setPackage("moe.shizuku.privileged.api"))
                } else {
                    log("Shizuku not installed")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                }
                return false
            }
            if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == 0) return true
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                log("Shizuku permission denied permanently — enable it inside Shizuku")
                return false
            }
            log("requesting Shizuku permission")
            awaitingStart = true
            Shizuku.requestPermission(REQ)
            return false
        } catch (e: Throwable) {
            log("Shizuku error: ${e.message}")
            return false
        }
    }

    /* ---------------- inject ---------------- */

    private fun start() {
        if (busy) { log("already running"); return }
        val idx = spinner.selectedItemPosition
        if (idx !in targets.indices) { log("no version selected"); return }
        val target = targets[idx]
        if (!target.installed(this)) { log("${target.label} is not installed"); return }

        startBtn.isEnabled = false
        log("preparing")

        if (!ensureShizuku()) {
            startBtn.isEnabled = true
            return
        }
        doStart()
    }

    private fun doStart() {
        if (busy) return
        val idx = spinner.selectedItemPosition
        if (idx !in targets.indices) { startBtn.isEnabled = true; return }
        val target = targets[idx]

        busy = true
        lifecycleScope.launch {
            try {
                val staged = withContext(Dispatchers.IO) { Payload.stage(this@MainActivity) }
                if (staged.isEmpty()) {
                    log("payload empty — staging failed")
                    return@launch
                }
                log("staged ${staged.size} file(s)")

                val push = withContext(Dispatchers.IO) { Injector.push(staged, target) }
                if (push.first != 0) {
                    log("inject failed: ${push.second.ifBlank { "exit ${push.first}" }}")
                    return@launch
                }
                log("injected ${staged.size} file(s) → ${target.dest}")

                launchGame(target)
                pending = staged to target

                for (i in 3 downTo 1) { log("wiping in ${i}s"); delay(1000) }
                runCatching { wipeNow() }
            } catch (e: Throwable) {
                log("error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
                startBtn.isEnabled = true
            }
        }
    }

    private fun launchGame(t: Target) {
        val i = packageManager.getLaunchIntentForPackage(t.pkg)
        if (i == null) { log("cannot launch ${t.label}"); return }
        startActivity(i)
        log("launched ${t.label}")
    }

    private suspend fun wipeNow() {
        val p = pending ?: return
        pending = null
        val r = withContext(Dispatchers.IO) { Injector.wipe(p.first, p.second) }
        log(if (r.first == 0) "files deleted from game" else "wipe failed: ${r.second}")
        withContext(Dispatchers.IO) {
            p.first.firstOrNull()?.parentFile?.let { Injector.sh("rm -rf '${it.absolutePath}'") }
        }
    }

    private fun log(s: String) {
        statusText.append("> $s\n")
        val sv = statusText.parent as? ScrollView ?: return
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
}
