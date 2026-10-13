# IonStack Root

One-tap root app for Samsung Galaxy S22 series (Android 12, kernel 5.10) using the [IonStack/CVE-2026-43499](https://github.com/sarabpal-dev/IonStack-S22U) exploit.

Instead of typing `adb shell` commands every boot, this app connects to the device's own ADB daemon over TCP and runs the exploit for you — with a live terminal log and post-root actions like restarting SystemUI or loading a KernelSU module.

## Prerequisites

### 1. Push exploit files to the device (one-time)

The exploit binaries must be present at `/data/local/tmp/` on the device. Push them via USB:

```sh
adb push cve-2026-43499       /data/local/tmp/
adb push cve-2026-43499-root  /data/local/tmp/
adb push cve-exp32            /data/local/tmp/
adb push kernelsu.ko          /data/local/tmp/
adb push patch_table.txt      /data/local/tmp/
adb push root.sh              /data/local/tmp/
adb shell chmod 755 /data/local/tmp/cve-2026-43499 /data/local/tmp/cve-2026-43499-root /data/local/tmp/cve-exp32
```

Build these from [IonStack-S22U](https://github.com/sarabpal-dev/IonStack-S22U) with `make PROJECT=<YOUR_TARGET> clean all` for your device's firmware. See that repo's README for target profiles and build instructions.

### 2. Enable persistent wireless ADB (one-time)

The app communicates with the on-device ADB daemon via TCP on port 5555. Enable it once:

```sh
# Option A — persistent across reboots (recommended):
adb shell setprop persist.adb.tcp.port 5555
adb reboot

# Option B — current session only:
adb tcpip 5555
```

After this, you no longer need a USB cable. The app connects to `localhost:5555` on the device itself.

### 3. Install the app

Install the APK via `adb install` or sideload it:

```sh
adb install IonStackRoot-debug.apk
```

## Usage

1. **Tap "Root Device"** — the app connects to ADB, runs the exploit, and streams the log output. On first launch you'll see an "Allow USB debugging?" dialog on the device — tap Allow (and check "Always allow").

2. **Tap "Load KernelSU"** — loads the KernelSU-Next kernel module via `insmod`. Open the [KernelSU Next manager](https://github.com/sarabpal-dev/KernelSU-Next/releases/tag/v3.3.0-android12-5.10) app to manage superuser access.

3. **Tap "Restart SystemUI"** — kills and restarts SystemUI. Useful after granting root to system apps or applying overlays.

Root does not survive a reboot. Re-open the app and tap "Root Device" again after each restart.

## Building

Open in Android Studio and run, or build from the command line:

```sh
# Generate Gradle wrapper (if not present):
gradle wrapper --gradle-version 8.2

# Build debug APK:
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, push to GitHub and the included Actions workflow builds the APK automatically — download it from the Actions artifacts tab.

## How it works

The app embeds a minimal ADB protocol client that connects to the device's own `adbd` daemon over TCP (`127.0.0.1:5555`). Commands execute as the `shell` user (uid 2000) with the `u:r:shell:s0` SELinux context — identical to running `adb shell` from a computer. This sidesteps Android's app sandbox restrictions that would otherwise block the exploit from running inside an app process.

Authentication uses a locally generated 2048-bit RSA keypair stored in the app's private preferences. The first connection triggers the standard "Allow USB debugging?" prompt.

## License

[MIT](LICENSE)
