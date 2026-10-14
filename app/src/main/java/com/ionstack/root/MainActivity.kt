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
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var rootBtn: Button
    private lateinit var sysUiBtn: Button
    private lateinit var ksuBtn: Button

    // persistent ANSI state across log() calls
    private var ansiColor: Int = COLOR_SHELL
    private var ansiBold: Boolean = false

    private val nativeDir by lazy { applicationInfo.nativeLibraryDir }
    private val dataDir by lazy { filesDir.absolutePath }

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

        extractAssets()
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

        private val ASSETS = listOf("kernelsu.ko", "patch_table.txt")
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

    private fun extractAssets() {
        for (name in ASSETS) {
            val dest = File(filesDir, name)
            if (!dest.exists()) {
                assets.open(name).use { inp ->
                    dest.outputStream().use { out -> inp.copyTo(out) }
                }
            }
        }
    }

    private fun runShell(cmd: String): String {
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val sb = StringBuilder()
        process.inputStream.bufferedReader().use { reader ->
            val buf = CharArray(4096)
            var n: Int
            while (reader.read(buf).also { n = it } != -1) {
                val chunk = String(buf, 0, n)
                sb.append(chunk)
                log(chunk)
            }
        }
        process.waitFor()
        return sb.toString()
    }

    private fun rootExec(cmd: String): String {
        val out = runShell("'$nativeDir/libionstack_root.so' -c '$cmd'")
        if ("Permission denied" !in out) return out
        logApp("[*] Exploit daemon unavailable, trying su…\n")
        return runShell("su -c '$cmd'")
    }

    // -- startup check -------------------------------------------------------

    private fun checkRootOnStartup() {
        Thread {
            try {
                val process = ProcessBuilder("sh", "-c", "cat /proc/modules 2>/dev/null")
                    .redirectErrorStream(true).start()
                val modules = process.inputStream.bufferedReader().readText()
                process.waitFor()

                if ("kernelsu" in modules) {
                    setStatus("Rooted", Color.parseColor("#00e676"))
                    runOnUiThread {
                        sysUiBtn.visibility = View.VISIBLE
                        ksuBtn.visibility = View.VISIBLE
                    }
                    logApp("[*] KernelSU module loaded — already rooted\n")
                }
            } catch (_: Exception) { }
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
        setStatus("Rooting…", Color.YELLOW)

        Thread {
            try {
                logApp("[*] Running exploit…\n\n")

                val script = "LD_PRELOAD='$nativeDir/libionstack_exp32.so'" +
                    " '$nativeDir/libionstack_exploit.so'" +
                    " && '$nativeDir/libionstack_root.so' -c 'id'" +
                    " && '$nativeDir/libionstack_root.so' -c" +
                    " 'insmod $dataDir/kernelsu.ko'"
                val output = runShell(script)

                if ("exploit succeeded" in output) {
                    setStatus("Rooted", Color.parseColor("#00e676"))
                    runOnUiThread {
                        sysUiBtn.visibility = View.VISIBLE
                        ksuBtn.visibility = View.VISIBLE
                    }
                    logApp("\n[*] ROOT SUCCESSFUL\n")
                } else {
                    setStatus("Root failed", Color.RED)
                    logApp("\n[!] Exploit did not report success\n")
                }
            } catch (e: Exception) {
                logApp("\n[!] ${e.message}\n")
                setStatus("Error", Color.RED)
            }
            runOnUiThread { rootBtn.isEnabled = true }
        }.start()
    }

    private fun doRestartSystemUI() {
        sysUiBtn.isEnabled = false
        Thread {
            try {
                logApp("\n[*] Restarting SystemUI…\n")
                rootExec("pkill -f com.android.systemui")
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
                logApp("\n[*] Loading KernelSU module…\n")
                rootExec("insmod $dataDir/kernelsu.ko")
                logApp("[*] Check KernelSU manager\n")
            } catch (e: Exception) {
                logApp("[!] ${e.message}\n")
            }
            runOnUiThread { ksuBtn.isEnabled = true }
        }.start()
    }
}
