package dev.ionstack.root;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stages bundled payloads into /data/local/tmp via Shizuku.
 *
 * Layout: assets/payload/<BUILD>/ holds the exploit library and root helper
 * built for that firmware; assets/payload/common/ holds the KernelSU late-load
 * files shared by every target. The exploit stage (cve-exp32/cve-exp64) is
 * embedded inside the library, so it is not staged separately.
 */
final class PayloadStore {

    interface Log {
        void line(String text);
    }

    static final String REMOTE_DIR = "/data/local/tmp";

    private static final Map<String, String> KSU_FILES = new LinkedHashMap<>();

    static {
        KSU_FILES.put("kernelsu.ko", "payload/common/kernelsu.ko");
        KSU_FILES.put("patch_table.txt", "payload/common/patch_table.txt");
        KSU_FILES.put("kernelsu.sh", "payload/common/kernelsu.sh");
    }

    private PayloadStore() {
    }

    static String remotePath(String name) {
        return REMOTE_DIR + "/" + name;
    }

    private static Map<String, String> payloadFiles(Targets.Target target) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("cve-2026-43499", "payload/" + target.build + "/cve-2026-43499");
        files.put("cve-2026-43499-root", "payload/" + target.build + "/cve-2026-43499-root");
        return files;
    }

    /** Both firmware-specific binaries are present and byte-identical to the assets. */
    static boolean isStaged(Targets.Target target, AssetManager assets) {
        if (target == null) {
            return false;
        }
        try {
            for (Map.Entry<String, String> entry : payloadFiles(target).entrySet()) {
                String remote = remotePath(entry.getKey());
                if (!digest(assets, entry.getValue()).equalsIgnoreCase(remoteDigest(remote))) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void stageAll(Targets.Target target, AssetManager assets, Log log) throws Exception {
        if (target == null) {
            throw new IOException("no payload for this firmware");
        }
        stage(payloadFiles(target), assets, log);
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
