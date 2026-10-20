# S908E IonStack Root

Android app project for running the S908E IonStack payload flow on **SM-S908E / S908EXXSEGZH4** devices through [Shizuku](https://shizuku.rikka.app/).

## What this project contains

- Android app module: `app`
- Bundled payload assets under `app/src/main/assets/payload`
- Java 17 + Android Gradle plugin build
- Helper build script: `build.sh`

## Requirements

- JDK 17+ (the included script can auto-select Java 21 on some ARM64 Linux hosts)
- Android SDK with platform/build tools available to Gradle
- For ARM64 hosts, an ARM64-compatible `aapt2` binary (set `AAPT2_OVERRIDE` if needed)

## Build

From the repository root:

```bash
./build.sh
```

Or directly with Gradle:

```bash
./gradlew :app:assembleDebug
```

The debug APK is generated under:

`/home/runner/work/IonStackRoot/IonStackRoot/app/build/outputs/apk/debug/`

## Notes

- The app checks device model/build and is targeted to a specific firmware.
- Use this project only on devices you own and are authorized to test.
