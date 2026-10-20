package dev.ionstack.s908e;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Stages bundled payloads into /data/local/tmp via Shizuku. */
final class PayloadStore {

    interface Log {
        void line(String text);
    }

    static final String REMOTE_DIR = "/data/local/tmp";

    private static final Map<String, String> FILES = new LinkedHashMap<>();
    private static final Map<String, String> KSU_FILES = new LinkedHashMap<>();

    static {
        FILES.put("cve-2026-43499", "payload/cve-2026-43499");
        FILES.put("cve-2026-43499-root", "payload/cve-2026-43499-root");
        FILES.put("cve-exp32", "payload/cve-exp32");

        KSU_FILES.put("kernelsu.ko", "payload/kernelsu.ko");
        KSU_FILES.put("patch_table.txt", "payload/patch_table.txt");
        KSU_FILES.put("kernelsu.sh", "payload/kernelsu.sh");
    }

    private PayloadStore() {
    }

    static String remotePath(String name) {
        return REMOTE_DIR + "/" + name;
    }

    static void stageAll(AssetManager assets, Log log) throws Exception {
        stage(FILES, assets, log);
    }

    static void stageKernelSu(AssetManager assets, Log log) throws Exception {
        stage(KSU_FILES, assets, log);
    }

    /**
     * KernelSU files may have been left behind by an earlier root-side run.
     * /data/local/tmp has the sticky bit, so uid 2000 cannot overwrite or
     * remove a root-owned leftover. This command must run through the root
     * helper before staging.
     */
    static String kernelSuCleanupCommand() {
        StringBuilder cmd = new StringBuilder("rm -rf");
        for (String name : KSU_FILES.keySet()) {
            cmd.append(' ').append(ShizukuBridge.shellQuote(remotePath(name)));
        }
        return cmd.toString();
    }

    static boolean isStaged() {
        return allExist(new String[]{
                remotePath("cve-2026-43499"),
                remotePath("cve-2026-43499-root"),
                remotePath("cve-exp32"),
        });
    }

    private static void stage(Map<String, String> files, AssetManager assets, Log log) throws Exception {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String name = entry.getKey();
            String asset = entry.getValue();
            String remote = remotePath(name);
            String localDigest = digest(assets, asset);
            log.line("[*] staging " + remote + " (" + readableSize(assets, asset) + ")");
            try (InputStream in = assets.open(asset)) {
                ShizukuBridge.writeFile(remote, "755", in);
            }
            String remoteDigest = remoteDigest(remote);
            if (!localDigest.equalsIgnoreCase(remoteDigest)) {
                throw new IOException("hash mismatch for " + remote
                        + " (local " + localDigest + ", remote " + remoteDigest + ")");
            }
            log.line("[+] " + remote + " sha256=" + localDigest);
        }
    }

    private static boolean allExist(String[] paths) {
        StringBuilder cmd = new StringBuilder();
        for (String path : paths) {
            cmd.append("test -x ").append(ShizukuBridge.shellQuote(path)).append(" && ");
        }
        cmd.append("echo STAGED");
        try {
            String out = ShizukuBridge.capture(
                    new String[]{"/system/bin/sh", "-c", cmd.toString()}, null, REMOTE_DIR);
            return out.contains("STAGED");
        } catch (Exception e) {
            return false;
        }
    }

    private static String remoteDigest(String remote) throws Exception {
        String out = ShizukuBridge.capture(new String[]{"/system/bin/sh", "-c",
                        "sha256sum " + ShizukuBridge.shellQuote(remote)}, null, REMOTE_DIR).trim();
        String[] parts = out.split("\\s+");
        if (parts.length == 0 || !parts[0].matches("[0-9a-fA-F]{64}")) {
            throw new IOException("sha256sum produced no usable output for " + remote
                    + (out.isEmpty() ? " (empty)" : " (" + out + ")"));
        }
        return parts[0];
    }

    private static String digest(AssetManager assets, String asset) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = assets.open(asset)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }

    private static String readableSize(AssetManager assets, String asset) {
        try (InputStream in = assets.open(asset)) {
            int size = in.available();
            return size > 0 ? size + " bytes" : "unknown size";
        } catch (IOException e) {
            return "unknown size";
        }
    }
}
