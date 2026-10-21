package dev.ionstack.root;

import java.util.Locale;

/**
 * Firmware table for the bundled IonStack payloads.
 *
 * Each target is one firmware build (the PDA string Samsung puts in
 * Build.FINGERPRINT / Build.VERSION.INCREMENTAL, e.g. "S908EXXSEGZH4") and
 * maps to a directory under assets/payload/<BUILD>/ containing that build's
 * exploit library and root helper.
 *
 * Only S908EXXSEGZH4 has been run on hardware, so everything else is marked
 * untested and the UI asks the user to report the result.
 */
final class Targets {

    /** Where test reports go. */
    static final String REPORT_URL = "https://github.com/fragtion/IonStackRoot";

    static final class Target {
        final String build;
        final String model;
        final String soc;
        final String android;
        final boolean verified;

        Target(String build, String model, String soc, String android, boolean verified) {
            this.build = build;
            this.model = model;
            this.soc = soc;
            this.android = android;
            this.verified = verified;
        }

        String label() {
            return model + " / " + build;
        }

        /** "SM-S908E, Snapdragon 8 Gen 1, Android 16" */
        String describe() {
            StringBuilder out = new StringBuilder(model);
            if (soc != null) {
                out.append(", ").append(soc);
            }
            if (android != null) {
                out.append(", Android ").append(android);
            }
            return out.toString();
        }
    }

    /** Verified first, then alphabetical by build. */
    private static final Target[] ALL = {
            new Target("S908EXXSEGZH4", "SM-S908E", "Snapdragon 8 Gen 1", "16", true),

            new Target("M556BXXU4AYB4", "SM-M556B", null, null, false),
            new Target("S901BXXSNGZD7", "SM-S901B", "Exynos 2200", "16", false),
            new Target("S901EXXSEGZE3", "SM-S901E", "Snapdragon 8 Gen 1", "16", false),
            new Target("S901NKSS9GZE5", "SM-S901N", "Snapdragon 8 Gen 1", "16", false),
            new Target("S901U1UESAGZF3", "SM-S901U1", "Snapdragon 8 Gen 1", "16", false),
            new Target("S901U1UESAGZH3", "SM-S901U1", "Snapdragon 8 Gen 1", "16", false),
            new Target("S901USQSAGZF3", "SM-S901U", "Snapdragon 8 Gen 1", "16", false),
            new Target("S901USQSAGZH3", "SM-S901U", "Snapdragon 8 Gen 1", "16", false),
            new Target("S901USQU2BVK1", "SM-S901U", "Snapdragon 8 Gen 1", "13", false),
            new Target("S901WVLS4DWL3", "SM-S901W", "Snapdragon 8 Gen 1", "14", false),
            new Target("S901WVLSAGZH3", "SM-S901W", "Snapdragon 8 Gen 1", "16", false),
            new Target("S9060ZCS9GZA1", "SM-S9060", "Snapdragon 8 Gen 1", "16", false),
            new Target("S906EXXSEGZE3", "SM-S906E", "Snapdragon 8 Gen 1", "16", false),
            new Target("S908BXXSMGZB2", "SM-S908B", "Exynos 2200", "16", false),
            new Target("S908BXXSNGZD7", "SM-S908B", "Exynos 2200", "16", false),
            new Target("S908BXXSOGZH3", "SM-S908B", "Exynos 2200", null, false),
            new Target("S908EXXSEGZE3", "SM-S908E", "Snapdragon 8 Gen 1", "16", false),
            new Target("S908NKSS9GZE5", "SM-S908N", "Snapdragon 8 Gen 1", "16", false),
            new Target("S908WVLS8FYG7", "SM-S908W", "Snapdragon 8 Gen 1", "15", false),
            new Target("S908WVLSAGZE3", "SM-S908W", "Snapdragon 8 Gen 1", "16", false),
            new Target("SCG14KDS1EZE3", "SCG14", "Snapdragon 8 Gen 1", "16", false),
            new Target("X900XXU9DYE5", "SM-X900", "Snapdragon 8 Gen 1", "15", false),
    };

    private Targets() {
    }

    static Target[] all() {
        return ALL.clone();
    }

    static int count() {
        return ALL.length;
    }

    static Target byBuild(String build) {
        if (build == null) {
            return null;
        }
        String wanted = build.trim().toUpperCase(Locale.US);
        for (Target target : ALL) {
            if (target.build.equals(wanted)) {
                return target;
            }
        }
        return null;
    }

    /**
     * Finds the target whose firmware build appears in the device identity
     * strings. Samsung puts the PDA in the fingerprint tail and in
     * VERSION.INCREMENTAL; DISPLAY/ID are checked too because a few One UI
     * builds carry it there instead.
     */
    static Target detect(String model, String... identity) {
        if (identity == null) {
            return null;
        }
        // Earlier identity strings are more authoritative: the fingerprint wins
        // over the incremental version if they ever disagree mid-update.
        for (String value : identity) {
            if (value == null) {
                continue;
            }
            String upper = value.toUpperCase(Locale.US);
            for (Target target : ALL) {
                if (upper.contains(target.build)) {
                    return target;
                }
            }
        }
        return null;
    }
}
