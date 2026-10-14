# IonStack Root

One-tap root app for Samsung Galaxy S22 series (Android 12, kernel 5.10) using the [IonStack/CVE-2026-43499](https://github.com/sarabpal-dev/IonStack-S22U) exploit.

The exploit binaries and KernelSU module are bundled inside the APK. Just install and tap Root — no ADB, no USB cable, no terminal commands.

## Building with your target's binaries

The exploit binaries are target-specific. Build them from [IonStack-S22U](https://github.com/sarabpal-dev/IonStack-S22U) with `make PROJECT=<YOUR_TARGET> clean all`, then place them in the project:

```
app/src/main/jniLibs/arm64-v8a/
├── libionstack_exploit.so    ← cve-2026-43499
├── libionstack_root.so       ← cve-2026-43499-root
└── libionstack_exp32.so      ← cve-exp32

app/src/main/assets/
├── kernelsu.ko               ← KernelSU-Next LKM
└── patch_table.txt           ← KASLR patch table (empty if not needed)
```

The native binaries must be renamed with a `lib` prefix and `.so` suffix — Android only extracts native libs with that naming convention.

Then build the APK:

```sh
gradle wrapper --gradle-version 8.2
./gradlew assembleDebug
```

## Usage

1. **Tap "Root Device"** — runs the exploit and loads KernelSU, streaming the log output.

2. **Tap "Load KernelSU"** — loads the KernelSU-Next kernel module via `insmod`. Open the [KernelSU Next manager](https://github.com/sarabpal-dev/KernelSU-Next/releases/tag/v3.3.0-android12-5.10) app to manage superuser access.

3. **Tap "Restart SystemUI"** — kills and restarts SystemUI. Useful after granting root to system apps or applying overlays.

Root does not survive a reboot. Re-open the app and tap "Root Device" again after each restart.

## How it works

The exploit binaries are packaged as native libraries inside the APK. Android extracts them to the app's native library directory with execute permission, bypassing the `untrusted_app` SELinux restriction that normally prevents apps from executing arbitrary binaries. The app runs the exploit directly via `ProcessBuilder` — no ADB daemon, no TCP connection, no authentication.

The ion allocator device (`/dev/ion`) is accessible from app context (it's used by all Android apps for GPU/camera buffer sharing), which is what makes this exploit class viable from within an app.

## License

[MIT](LICENSE)
