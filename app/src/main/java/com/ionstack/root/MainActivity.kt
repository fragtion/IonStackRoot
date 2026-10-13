package com.ionstack.root

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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

    // -- helpers -------------------------------------------------------------

    private fun log(text: String) = runOnUiThread {
        logView.append(text)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setStatus(label: String, color: Int) = runOnUiThread {
        statusText.text = label
        (statusDot.background as GradientDrawable).setColor(color)
    }

    private fun connectAdb(promptAuth: Boolean = true): AdbShell {
        adb?.let { if (it.isConnected) return it }
        val client = AdbShell(this) { msg -> log(msg) }
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
                log("[*] Checking root status…\n")
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
                    log("[*] Already rooted: ${result.trim()}\n")
                } else {
                    log("[*] Not rooted yet\n")
                }
            } catch (e: Exception) {
                log("[*] ADB check: ${e.message}\n")
            }
        }.start()
    }

    // -- actions -------------------------------------------------------------

    private fun doRoot() {
        rootBtn.isEnabled = false
        sysUiBtn.visibility = View.GONE
        ksuBtn.visibility = View.GONE
        logView.text = ""
        setStatus("Connecting…", Color.YELLOW)

        Thread {
            try {
                log("[*] Connecting to ADB (localhost:5555)…\n")
                log("[*] If prompted, tap 'Allow USB debugging' on the device\n\n")
                val client = connectAdb()
                log("[*] ADB connected\n\n")
                setStatus("Rooting…", Color.YELLOW)

                shellRun(client, "sh /data/local/tmp/root.sh")

                log("\n[*] Verifying root…\n")
                val sb = StringBuilder()
                client.shellExec(
                    "/data/local/tmp/cve-2026-43499-root -c 'id'"
                ) { sb.append(it) }
                val id = sb.toString()
                log("[*] $id\n")

                if ("uid=0" in id) {
                    setStatus("Rooted", Color.parseColor("#00e676"))
                    runOnUiThread {
                        sysUiBtn.visibility = View.VISIBLE
                        ksuBtn.visibility = View.VISIBLE
                    }
                    log("\n[*] ROOT SUCCESSFUL\n")
                } else {
                    setStatus("Root failed", Color.RED)
                    log("\n[!] Root verification failed\n")
                }
            } catch (e: Exception) {
                log("\n[!] ${e.message}\n")
                setStatus("Error", Color.RED)

                if (e.message?.contains("refused", ignoreCase = true) == true ||
                    e.message?.contains("unreachable", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true) {
                    log("\n[!] Cannot reach ADB on localhost:5555\n")
                    log("[!] Make sure wireless ADB is enabled:\n")
                    log("[!]   adb tcpip 5555\n")
                    log("[!] Or for persistent:\n")
                    log("[!]   adb shell setprop persist.adb.tcp.port 5555\n")
                    log("[!]\n")
                    log("[!] If ADB is connected from another PC,\n")
                    log("[!] disconnect it first (adb disconnect) and retry.\n")
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
                log("\n[*] Restarting SystemUI…\n")
                shellRun(client,
                    "/data/local/tmp/cve-2026-43499-root -c 'pkill -f com.android.systemui'")
                log("[*] SystemUI restarted\n")
            } catch (e: Exception) {
                log("[!] ${e.message}\n")
            }
            runOnUiThread { sysUiBtn.isEnabled = true }
        }.start()
    }

    private fun doLoadKsu() {
        ksuBtn.isEnabled = false
        Thread {
            try {
                val client = connectAdb()
                log("\n[*] Loading KernelSU module…\n")
                shellRun(client,
                    "/data/local/tmp/cve-2026-43499-root -c 'insmod /data/local/tmp/kernelsu.ko'")
                log("[*] Check KernelSU manager\n")
            } catch (e: Exception) {
                log("[!] ${e.message}\n")
            }
            runOnUiThread { ksuBtn.isEnabled = true }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        adb?.disconnect()
    }
}
