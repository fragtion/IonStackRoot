package dev.ionstack.s908e;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-click runner for the S908EXXSEGZH4 (SM-S908E, 5.10 b0q) IonStack payload.
 *
 * The payloads are built for the Android shell user (uid 2000), so everything is
 * staged into /data/local/tmp and executed through Shizuku. Payload execution and
 * shell-bridge structure follow BuSung-dev/Root-My-Galaxy (Apache-2.0).
 */
public class MainActivity extends Activity implements PayloadStore.Log {

    private static final int REQUEST_SHIZUKU = 0x5352;
    private static final String SHELL_PATH =
            "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:"
                    + "/apex/com.android.virt/bin:/system_ext/bin:/system/bin:/system/xbin:"
                    + "/odm/bin:/vendor/bin:/vendor/xbin";
    private static final long MAX_RUNTIME_MS = 15 * 60 * 1000L;
    private static final int LOG_LIMIT = 150_000;
    private static final String UI_PREFS = "ionstack-ui";
    private static final String PREF_DARK = "dark";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final StringBuilder logBuffer = new StringBuilder();

    private TextView statusView;
    private TextView gateView;
    private TextView logView;
    private ScrollView logScroll;
    private boolean followLogs = true;
    private Button grantButton;
    private Button stageButton;
    private Button runButton;
    private Button stopButton;
    private Button loadKsuButton;
    private Button checkButton;
    private Button commandButton;
    private Button restartUiButton;
    private EditText commandInput;
    private CheckBox forceCheck;
    private CheckBox darkCheck;
    private volatile Process activeProcess;

    private boolean isDarkEnabled() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (prefs.contains(PREF_DARK)) {
            return prefs.getBoolean(PREF_DARK, false);
        }
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(isDarkEnabled() ? android.R.style.Theme_Material
                : android.R.style.Theme_Material_Light);
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        DeviceGate.Result gate = DeviceGate.check();
        gateView.setText(gate.detail);
        appendLog("S908E IonStack runner\n");
        appendLog("Payload: S908EXXSEGZH4 (SM-S908E, b0q 5.10)\n");
        appendLog("Transport: Shizuku shell (uid 2000)\n\n");
        refreshStatus();
        executor.execute(() -> {
            boolean staged = ShizukuBridge.isRunning() && ShizukuBridge.isGranted()
                    && PayloadStore.isStaged();
            ui.post(() -> appendLog(staged
                    ? "[+] payloads already staged in /data/local/tmp\n"
                    : "[*] payloads not staged yet\n"));
        });
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("S908E IonStack Root");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusView);

        gateView = new TextView(this);
        gateView.setTextSize(12);
        gateView.setTypeface(Typeface.MONOSPACE);
        gateView.setPadding(0, 0, 0, dp(8));
        root.addView(gateView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        grantButton = button("Grant Shizuku");
        grantButton.setOnClickListener(v -> requestShizuku());
        stageButton = button("Stage payloads");
        stageButton.setOnClickListener(v -> stagePayloads());
        row1.addView(grantButton, weight());
        row1.addView(stageButton, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        runButton = button("Run exploit");
        runButton.setOnClickListener(v -> confirmRun());
        stopButton = button("Stop");
        stopButton.setOnClickListener(v -> stopActive());
        row2.addView(runButton, weight());
        row2.addView(stopButton, weight());
        root.addView(row2);

        loadKsuButton = button("Load KernelSU");
        loadKsuButton.setOnClickListener(v -> confirmKernelSu());
        root.addView(loadKsuButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        forceCheck = new CheckBox(this);
        forceCheck.setText("Force run on non-matching firmware");
        forceCheck.setTextSize(12);
        root.addView(forceCheck);

        darkCheck = new CheckBox(this);
        darkCheck.setText("Dark mode");
        darkCheck.setTextSize(12);
        darkCheck.setChecked(isDarkEnabled());
        darkCheck.setOnCheckedChangeListener((buttonView, checked) -> {
            getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_DARK, checked).apply();
            recreate();
        });
        root.addView(darkCheck);

        TextView rootLabel = new TextView(this);
        rootLabel.setText("Root command (after a successful run)");
        rootLabel.setTextSize(14);
        rootLabel.setPadding(0, dp(8), 0, 0);
        root.addView(rootLabel);

        commandInput = new EditText(this);
        commandInput.setHint("id");
        commandInput.setText("id");
        commandInput.setSingleLine(true);
        commandInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(commandInput);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        commandButton = button("Run as root");
        commandButton.setOnClickListener(v -> runRootCommand());
        checkButton = button("Check root");
        checkButton.setOnClickListener(v -> {
            commandInput.setText("id");
            runRootCommand();
        });
        row3.addView(commandButton, weight());
        row3.addView(checkButton, weight());
        root.addView(row3);

        restartUiButton = button("Restart SystemUI (root)");
        restartUiButton.setOnClickListener(v -> restartSystemUi());
        root.addView(restartUiButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(8);
        logScroll.setLayoutParams(scrollParams);
        logScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) ->
                followLogs = isLogAtBottom());

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logScroll);

        return root;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshStatus() {
        boolean running = ShizukuBridge.isRunning();
        boolean granted = running && ShizukuBridge.isGranted();
        statusView.setText("Shizuku: " + (running ? (granted ? "running, granted" : "running, permission needed")
                : "not running") +
                "   |   Payload: " + (busy.get() ? "running" : "idle"));
        grantButton.setEnabled(!busy.get());
        stageButton.setEnabled(!busy.get() && running && granted);
        runButton.setEnabled(!busy.get() && running && granted);
        stopButton.setEnabled(busy.get());
        loadKsuButton.setEnabled(!busy.get() && running && granted);
        commandButton.setEnabled(!busy.get() && running && granted);
        checkButton.setEnabled(!busy.get() && running && granted);
        restartUiButton.setEnabled(!busy.get() && running && granted);
    }

    private void setBusy(boolean value) {
        Runnable apply = () -> {
            busy.set(value);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (value) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            refreshStatus();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            ui.post(apply);
        }
    }

    private void requestShizuku() {
        if (!ShizukuBridge.isRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("Shizuku is not running")
                    .setMessage("Start Shizuku as the shell user first.\n\n"
                            + "Without a PC: enable Developer options > Wireless debugging, "
                            + "pair the device through Shizuku's on-device pairing flow, and start "
                            + "the service. With a PC: run `adb shell sh /storage/emulated/0/Android/data/"
                            + "moe.shizuku.privileged.api/start.sh` (or use Shizuku's ADB instructions).\n\n"
                            + "This app never runs the exploit from its own sandbox.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        ShizukuBridge.requestPermission(REQUEST_SHIZUKU, (granted, error) -> runOnUiThread(() -> {
            appendLog(granted ? "[+] Shizuku permission granted\n"
                    : "[-] " + (error == null ? "Shizuku permission denied" : error) + "\n");
            refreshStatus();
        }));
    }

    private void stagePayloads() {
        if (busy.get()) {
            return;
        }
        executor.execute(() -> {
            setBusy(true);
            try {
                appendLog("[*] staging payloads over Shizuku\n");
                PayloadStore.stageAll(getAssets(), this);
                appendLog("[+] staging complete\n");
            } catch (Throwable t) {
                appendLog("[-] staging failed: " + t.getMessage() + "\n");
            } finally {
                setBusy(false);
            }
        });
    }

    private void confirmRun() {
        DeviceGate.Result gate = DeviceGate.check();
        if (!gate.matches && !forceCheck.isChecked()) {
            new AlertDialog.Builder(this)
                    .setTitle("Firmware does not match")
                    .setMessage(gate.detail + "\n\nThis payload is built for SM-S908E "
                            + "S908EXXSEGZH4 only. Refusing to run.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Run the exploit?")
                .setMessage("The race stage is timing sensitive and can fail or panic the kernel. "
                        + "Reboot first, close background apps, keep the screen unlocked and do not "
                        + "touch the phone while it runs.\n\nRun only on a device you own.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run", (d, w) -> executeExploit())
                .show();
    }

    private void confirmKernelSu() {
        DeviceGate.Result gate = DeviceGate.check();
        if (!gate.matches && !forceCheck.isChecked()) {
            new AlertDialog.Builder(this)
                    .setTitle("Firmware does not match")
                    .setMessage(gate.detail + "\n\nThis module is built for SM-S908E "
                            + "S908EXXSEGZH4 only. Refusing to load it.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Load KernelSU?")
                .setMessage("Late-loads kernelsu.ko through the root helper and verifies "
                        + "/proc/modules. Run the exploit first; the module is volatile until "
                        + "reboot. A failed late-load can panic the kernel.\n\n"
                        + "Run only on a device you own.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Load", (d, w) -> executeKernelSu())
                .show();
    }

    private void executeKernelSu() {
        if (busy.get()) {
            return;
        }
        if (!ShizukuBridge.isRunning() || !ShizukuBridge.isGranted()) {
            requestShizuku();
            return;
        }
        executor.execute(() -> {
            setBusy(true);
            appendLog("\n[*] KernelSU late-load\n");
            try {
                if (!PayloadStore.isStaged()) {
                    appendLog("[*] exploit payloads missing, staging first\n");
                    PayloadStore.stageAll(getAssets(), this);
                }

                String helper = PayloadStore.remotePath("cve-2026-43499-root");
                String rootCheck = ShizukuBridge.capture(
                        new String[]{helper, "-c", "id"}, null, PayloadStore.REMOTE_DIR).trim();
                appendLog("[*] root check: " + rootCheck + "\n");
                if (!rootCheck.contains("uid=0")) {
                    appendLog("[-] root daemon is not ready; run the exploit first\n");
                    return;
                }

                // Leftovers from an earlier root-side attempt are root-owned and
                // cannot be replaced by uid 2000 in the sticky /data/local/tmp.
                String cleanup = ShizukuBridge.capture(new String[]{helper, "-c",
                        PayloadStore.kernelSuCleanupCommand()}, null, PayloadStore.REMOTE_DIR).trim();
                if (!cleanup.isEmpty()) {
                    appendLog("[*] cleanup: " + cleanup + "\n");
                }

                appendLog("[*] staging KernelSU files\n");
                PayloadStore.stageKernelSu(getAssets(), this);

                appendLog("[*] running kernelsu.sh as uid 2000\n");
                Process process = ShizukuBridge.exec(new String[]{
                        "/system/bin/sh", PayloadStore.remotePath("kernelsu.sh")
                }, null, PayloadStore.REMOTE_DIR);
                activeProcess = process;
                Thread stdout = pump(process.getInputStream(), "ksu-out");
                Thread stderr = pump(process.getErrorStream(), "ksu-err");
                int exit = process.waitFor();
                stdout.join(2000);
                stderr.join(2000);
                appendLog("[*] kernelsu.sh exited (" + exit + ")\n");

                String modules = ShizukuBridge.capture(
                        new String[]{helper, "-c", "grep '^kernelsu ' /proc/modules"},
                        null, PayloadStore.REMOTE_DIR).trim();
                appendLog("[*] /proc/modules: "
                        + (modules.isEmpty() ? "(no kernelsu line)" : modules) + "\n");
            } catch (Throwable t) {
                appendLog("[-] KernelSU load failed: " + t.getMessage() + "\n");
            } finally {
                activeProcess = null;
                setBusy(false);
            }
        });
    }

    private void executeExploit() {
        if (busy.get()) {
            return;
        }
        if (!ShizukuBridge.isRunning() || !ShizukuBridge.isGranted()) {
            requestShizuku();
            return;
        }
        executor.execute(() -> {
            setBusy(true);
            appendLog("\n[*] checking staged payloads\n");
            try {
                if (!PayloadStore.isStaged()) {
                    appendLog("[*] payloads missing, staging first\n");
                    PayloadStore.stageAll(getAssets(), this);
                }
                String[] env = new String[]{
                        "PATH=" + SHELL_PATH,
                        "HOME=/data/local/tmp",
                        "LD_PRELOAD=" + PayloadStore.remotePath("cve-2026-43499"),
                        "EXPLOIT_ATTEMPTS=16",
                };
                appendLog("[*] starting exploit as uid 2000 (Shizuku)\n");
                long startedAt = System.currentTimeMillis();
                Process process = ShizukuBridge.exec(
                        new String[]{"/system/bin/sh", "-c", "true"}, env, "/data/local/tmp");
                activeProcess = process;
                Thread stdout = pump(process.getInputStream(), "out");
                Thread stderr = pump(process.getErrorStream(), "err");
                int exit;
                while (true) {
                    if (!process.isAlive()) {
                        try {
                            exit = process.waitFor();
                        } catch (Throwable t) {
                            exit = -999;
                        }
                        break;
                    }
                    if (System.currentTimeMillis() - startedAt > MAX_RUNTIME_MS) {
                        appendLog("[-] runtime limit reached, stopping\n");
                        process.destroy();
                        exit = -1;
                        break;
                    }
                    Thread.sleep(250);
                }
                stdout.join(2000);
                stderr.join(2000);
                activeProcess = null;
                appendLog("[*] exploit process exited (" + exit + ") after "
                        + ((System.currentTimeMillis() - startedAt) / 1000) + "s\n");
                if (exit == 0) {
                    appendLog("[+] supervisor reported success; checking the root daemon\n");
                    executeRootCommandBlocking("id");
                }
            } catch (Throwable t) {
                appendLog("[-] run failed: " + t.getMessage() + "\n");
            } finally {
                activeProcess = null;
                setBusy(false);
            }
        });
    }

    private void stopActive() {
        Process process = activeProcess;
        if (process != null) {
            appendLog("[*] stopping exploit process\n");
            process.destroy();
        }
    }

    private Thread pump(InputStream stream, String tag) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    String text = new String(buffer, 0, read);
                    ui.post(() -> appendLog(text));
                }
            } catch (IOException ignored) {
            }
        }, "payload-" + tag);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void restartSystemUi() {
        if (busy.get()) {
            return;
        }
        if (!ShizukuBridge.isRunning() || !ShizukuBridge.isGranted()) {
            requestShizuku();
            return;
        }
        appendLog("\n[*] restarting SystemUI\n");
        runRootCommandInternal("pkill -f com.android.systemui");
    }

    private void runRootCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            command = "id";
        }
        runRootCommandInternal(command);
    }

    private void runRootCommandInternal(String command) {
        if (!ShizukuBridge.isRunning() || !ShizukuBridge.isGranted()) {
            requestShizuku();
            return;
        }
        final String finalCommand = command;
        executor.execute(() -> {
            setBusy(true);
            try {
                executeRootCommandBlocking(finalCommand);
            } finally {
                activeProcess = null;
                setBusy(false);
            }
        });
    }

    private void executeRootCommandBlocking(String command) {
        try {
            appendLog("$ " + command + "\n");
            Process process = ShizukuBridge.exec(new String[]{
                    PayloadStore.remotePath("cve-2026-43499-root"), "-c", command
            }, null, "/data/local/tmp");
            activeProcess = process;
            Thread stdout = pump(process.getInputStream(), "root-out");
            Thread stderr = pump(process.getErrorStream(), "root-err");
            int exit = process.waitFor();
            stdout.join(2000);
            stderr.join(2000);
            appendLog("[*] root command exited (" + exit + ")\n");
        } catch (Throwable t) {
            appendLog("[-] root command failed: " + t.getMessage() + "\n");
        }
    }

    @Override
    public void line(String text) {
        appendLog(text + "\n");
    }

    private void appendLog(String text) {
        ui.post(() -> {
            boolean follow = followLogs;
            logBuffer.append(text);
            if (logBuffer.length() > LOG_LIMIT) {
                logBuffer.delete(0, logBuffer.length() - LOG_LIMIT / 2);
                logBuffer.insert(0, "[... earlier output dropped ...]\n");
            }
            logView.setText(logBuffer);
            if (follow) {
                // The ScrollView is the scroller; scrolling logView does nothing.
                logScroll.post(() -> logScroll.scrollTo(0, logView.getBottom()));
            }
        });
    }

    /** True when the user is parked at the newest log line. */
    private boolean isLogAtBottom() {
        View child = logScroll.getChildAt(0);
        if (child == null || logScroll.getHeight() == 0) {
            return true;
        }
        return child.getBottom() - (logScroll.getHeight() + logScroll.getScrollY()) <= dp(24);
    }

    @Override
    protected void onDestroy() {
        stopActive();
        executor.shutdownNow();
        super.onDestroy();
    }
}
