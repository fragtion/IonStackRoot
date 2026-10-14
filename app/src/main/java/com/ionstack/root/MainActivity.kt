package com.ionstack.root

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var rootBtn: Button
    private lateinit var sysUiBtn: Button
    private lateinit var ksuBtn: Button

    private var adb: AdbShell? = null

    // persistent ANSI state across log() calls
    private var ansiColor: Int = COLOR_SHELL
    private var ansiBold: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        rootBtn = findViewById(R.id.rootButton)
        sysUiBtn = findViewById(R.id.restartUiButton)
        ksuBtn = findViewById(R.id.loadKsuButton)

        logView.typeface = Typeface.MONOSPACE
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.GRAY)
        }

        rootBtn.setOnClickListener { doRoot() }
        sysUiBtn.setOnClickListener { doRestartSystemUI() }
        ksuBtn.setOnClickListener { doLoadKsu() }

        checkRootOnStartup()
    }

    // -- ANSI color parsing --------------------------------------------------

    companion object {
        private const val COLOR_APP = 0xFF00FF41.toInt()      // our messages: green
        private const val COLOR_SHELL = 0xFFE0E0E0.toInt()   // script output default: white

        private val ANSI_REGEX = Regex("\u001b\\[(\\d+(?:;\\d+)*)m")

        private val SGR_COLORS = mapOf(
            30 to 0xFF555555.toInt(),  // black
            31 to 0xFFFF5555.toInt(),  // red
            32 to 0xFF50FA7B.toInt(),  // green
            33 to 0xFFF1FA8C.toInt(),  // yellow
            34 to 0xFF8888FF.toInt(),  // blue
            35 to 0xFFFF79C6.toInt(),  // magenta
            36 to 0xFF8BE9FD.toInt(),  // cyan
            37 to 0xFFE0E0E0.toInt(),  // white
            90 to 0xFF888888.toInt(),  // bright black
            91 to 0xFFFF6E6E.toInt(),  // bright red
            92 to 0xFF69FF94.toInt(),  // bright green
            93 to 0xFFFFFFA5.toInt(),  // bright yellow
            94 to 0xFFD6ACFF.toInt(),  // bright blue
            95 to 0xFFFF92DF.toInt(),  // bright magenta
            96 to 0xFFA4FFFF.toInt(),  // bright cyan
            97 to 0xFFFFFFFF.toInt(),  // bright white
        )
    }

    private fun parseAnsi(text: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        var lastEnd = 0

        for (match in ANSI_REGEX.findAll(text)) {
            val before = text.substring(lastEnd, match.range.first)
            if (before.isNotEmpty()) appendStyled(ssb, before)

            for (code in match.groupValues[1].split(";").map { it.toIntOrNull() ?: 0 }) {
                when {
                    code == 0 -> { ansiColor = COLOR_SHELL; ansiBold = false }
                    code == 1 -> ansiBold = true
                    code == 22 -> ansiBold = false
                    code in SGR_COLORS -> ansiColor = SGR_COLORS[code]!!
                }
            }
            lastEnd = match.range.last + 1
        }

        val remaining = text.substring(lastEnd)
        if (remaining.isNotEmpty()) appendStyled(ssb, remaining)
        return ssb
    }

    private fun appendStyled(ssb: SpannableStringBuilder, text: String) {
        val start = ssb.length
        ssb.append(text)
        ssb.setSpan(ForegroundColorSpan(ansiColor), start, ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (ansiBold) {
            ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun log(text: String) = runOnUiThread {
        logView.append(parseAnsi(text))
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun logApp(text: String) = runOnUiThread {
        val ssb = SpannableStringBuilder(text)
        ssb.setSpan(ForegroundColorSpan(COLOR_APP), 0, ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logView.append(ssb)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(label: String, color: Int) = runOnUiThread {
        statusText.text = label
        (statusDot.background as GradientDrawable).setColor(color)
    }

    private fun connectAdb(promptAuth: Boolean = true): AdbShell {
        adb?.let { if (it.isConnected) return it }
        val client = AdbShell(this) { msg -> logApp(msg) }
        client.connect(promptAuth = promptAuth)
        adb = client
        return client
    }

    private fun shellRun(client: AdbShell, cmd: String): String {
        val sb = StringBuilder()
        client.shellExec(cmd) { chunk ->
            sb.append(chunk)
            log(chunk)
        }
        return sb.toString()
    }

    // -- startup check -------------------------------------------------------

    private fun checkRootOnStartup() {
        Thread {
            try {
                val client = connectAdb(promptAuth = false)
                logApp("[*] Checking root status…\n")
                val sb = StringBuilder()
                client.shellExec(
                    "/data/local/tmp/cve-2026-43499-root -c 'id'"
                ) { sb.append(it) }
                val result = sb.toString()

                if ("uid=0" in result) {
                    setStatus("Rooted", Color.parseColor("#00e676"))
                    runOnUiThread {
                        sysUiBtn.visibility = View.VISIBLE
                        ksuBtn.visibility = View.VISIBLE
                    }
                    logApp("[*] Already rooted: ${result.trim()}\n")
                } else {
                    logApp("[*] Not rooted yet\n")
                }
            } catch (e: Exception) {
                logApp("[*] ADB check: ${e.message}\n")
            }
        }.start()
    }

    // -- actions -------------------------------------------------------------

    private fun doRoot() {
        rootBtn.isEnabled = false
        sysUiBtn.visibility = View.GONE
        ksuBtn.visibility = View.GONE
        logView.text = ""
        ansiColor = COLOR_SHELL
        ansiBold = false
        setStatus("Connecting…", Color.YELLOW)

        Thread {
            try {
                logApp("[*] Connecting to ADB (localhost:5555)…\n")
                logApp("[*] If prompted, tap 'Allow USB debugging' on the device\n\n")
                val client = connectAdb()
                logApp("[*] ADB connected\n\n")
                setStatus("Rooting…", Color.YELLOW)

                shellRun(client, "sh /data/local/tmp/root.sh")

                logApp("\n[*] Verifying root…\n")
                var id = ""
                for (attempt in 1..5) {
                    val sb = StringBuilder()
                    client.shellExec(
                        "/data/local/tmp/cve-2026-43499-root -c 'id'"
                    ) { sb.append(it) }
                    id = sb.toString()
                    if ("uid=0" in id) break
                    logApp("[*] Waiting for root daemon (attempt $attempt/5)…\n")
                    Thread.sleep(2000)
                }
                logApp("[*] $id\n")

                if ("uid=0" in id) {
                    setStatus("Rooted", Color.parseColor("#00e676"))
                    runOnUiThread {
                        sysUiBtn.visibility = View.VISIBLE
                        ksuBtn.visibility = View.VISIBLE
                    }
                    logApp("\n[*] ROOT SUCCESSFUL\n")

                    // Inject our ADB key so future TCP connections auth without USB
                    try {
                        val keyLine = client.getPublicKeyLine()
                        shellRun(client,
                            "/data/local/tmp/cve-2026-43499-root -c 'grep -qF \"ionstack@root\"" +
                            " /data/misc/adb/adb_keys 2>/dev/null || echo \"$keyLine\"" +
                            " >> /data/misc/adb/adb_keys'")
                        logApp("[*] ADB key authorized for TCP auth\n")
                    } catch (_: Exception) { }
                } else {
                    setStatus("Root failed", Color.RED)
                    logApp("\n[!] Root verification failed\n")
                }
            } catch (e: Exception) {
                logApp("\n[!] ${e.message}\n")
                setStatus("Error", Color.RED)

                if (e.message?.contains("refused", ignoreCase = true) == true ||
                    e.message?.contains("unreachable", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true) {
                    logApp("\n[!] Cannot reach ADB on localhost:5555\n")
                    logApp("[!] Make sure wireless ADB is enabled:\n")
                    logApp("[!]   adb tcpip 5555\n")
                    logApp("[!] Or for persistent:\n")
                    logApp("[!]   adb shell setprop persist.adb.tcp.port 5555\n")
                    logApp("[!]\n")
                    logApp("[!] If ADB is connected from another PC,\n")
                    logApp("[!] disconnect it first (adb disconnect) and retry.\n")
                }
            }
            runOnUiThread { rootBtn.isEnabled = true }
        }.start()
    }

    private fun doRestartSystemUI() {
        sysUiBtn.isEnabled = false
        Thread {
            try {
                val client = connectAdb()
                logApp("\n[*] Restarting SystemUI…\n")
                shellRun(client,
                    "/data/local/tmp/cve-2026-43499-root -c 'pkill -f com.android.systemui'")
                logApp("[*] SystemUI restarted\n")
            } catch (e: Exception) {
                logApp("[!] ${e.message}\n")
            }
            runOnUiThread { sysUiBtn.isEnabled = true }
        }.start()
    }

    private fun doLoadKsu() {
        ksuBtn.isEnabled = false
        Thread {
            try {
                val client = connectAdb()
                logApp("\n[*] Loading KernelSU module…\n")
                shellRun(client,
                    "/data/local/tmp/cve-2026-43499-root -c 'insmod /data/local/tmp/kernelsu.ko'")
                logApp("[*] Check KernelSU manager\n")
            } catch (e: Exception) {
                logApp("[!] ${e.message}\n")
            }
            runOnUiThread { ksuBtn.isEnabled = true }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        adb?.disconnect()
    }
}
