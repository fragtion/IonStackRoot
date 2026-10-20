package dev.ionstack.s908e;

import android.os.Build;

/**
 * Guards against running a firmware-specific payload on the wrong build.
 *
 * /proc/version is not readable by app UIDs on modern Android (SELinux
 * proc_version), so the authoritative build check is Build.FINGERPRINT, which
 * embeds the PDA string (e.g. ".../S908EXXSEGZH4:user/release-keys").
 */
final class DeviceGate {

    static final String EXPECTED_MODEL = "SM-S908E";
    static final String EXPECTED_BUILD = "S908EXXSEGZH4";

    static final class Result {
        final boolean matches;
        final String model;
        final String fingerprint;
        final String kernel;
        final String detail;

        Result(boolean matches, String model, String fingerprint, String kernel, String detail) {
            this.matches = matches;
            this.model = model;
            this.fingerprint = fingerprint;
            this.kernel = kernel;
            this.detail = detail;
        }
    }

    private DeviceGate() {
    }

    static Result check() {
        String model = Build.MODEL == null ? "unknown" : Build.MODEL;
        String fingerprint = Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT;
        String kernel = System.getProperty("os.version", "unknown");
        boolean modelOk = EXPECTED_MODEL.equalsIgnoreCase(model);
        boolean buildOk = fingerprint.toUpperCase().contains(EXPECTED_BUILD);
        boolean matches = modelOk && buildOk;

        StringBuilder detail = new StringBuilder();
        detail.append(modelOk ? "[+] " : "[-] ").append("model ").append(model);
        if (!modelOk) {
            detail.append(" (expected ").append(EXPECTED_MODEL).append(")");
        }
        detail.append('\n');
        detail.append(buildOk ? "[+] " : "[-] ").append("build ").append(fingerprint);
        if (!buildOk) {
            detail.append(" (expected ").append(EXPECTED_BUILD).append(")");
        }
        detail.append('\n');
        detail.append("[i] kernel ").append(kernel);
        return new Result(matches, model, fingerprint, kernel, detail.toString());
    }
}
