# IonStack Root

One-click root app for Samsung devices on the 5.10 Android kernel, driven through
[Shizuku](https://shizuku.rikka.app/). It bundles the [IonStack / CVE-2026-43499
exploit](https://github.com/NebuSec/CyberMeowfia/tree/b850d3bddc74c3328d5fbcc0568d21962b55d949/IonStack/CVE-2026-43499/exploit)
built for every firmware in the target table, matches the running build, stages
that build's payload over Shizuku, and streams the exploit log.

Only **SM-S908E / S908EXXSEGZH4** has been verified on hardware. Every other
target is bundled but untested: the app warns before running it and asks the user
to report the outcome at <https://github.com/fragtion/IonStackRoot>. Reports of
successes and failures are what move a build into the verified column.

## Supported firmware

| Model | Build | SoC | Android | Status |
| :--- | :--- | :--- | :--- | :--- |
| SM-S908E | `S908EXXSEGZH4` | Snapdragon 8 Gen 1 | 16 | **verified** |
| SM-M556B | `M556BXXU4AYB4` | — | — | untested |
| SM-S901B | `S901BXXSNGZD7` | Exynos 2200 | 16 | untested |
| SM-S901E | `S901EXXSEGZE3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S901N | `S901NKSS9GZE5` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S901U1 | `S901U1UESAGZF3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S901U1 | `S901U1UESAGZH3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S901U | `S901USQSAGZF3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S901U | `S901USQSAGZH3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S901U | `S901USQU2BVK1` | Snapdragon 8 Gen 1 | 13 | untested |
| SM-S901W | `S901WVLS4DWL3` | Snapdragon 8 Gen 1 | 14 | untested |
| SM-S901W | `S901WVLSAGZH3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S9060 | `S9060ZCS9GZA1` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S906E | `S906EXXSEGZE3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S908B | `S908BXXSMGZB2` | Exynos 2200 | 16 | untested |
| SM-S908B | `S908BXXSNGZD7` | Exynos 2200 | 16 | untested |
| SM-S908B | `S908BXXSOGZH3` | Exynos 2200 | — | untested |
| SM-S908E | `S908EXXSEGZE3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S908N | `S908NKSS9GZE5` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-S908W | `S908WVLS8FYG7` | Snapdragon 8 Gen 1 | 15 | untested |
| SM-S908W | `S908WVLSAGZE3` | Snapdragon 8 Gen 1 | 16 | untested |
| SCG14 | `SCG14KDS1EZE3` | Snapdragon 8 Gen 1 | 16 | untested |
| SM-X900 | `X900XXU9DYE5` | Snapdragon 8 Gen 1 | 15 | untested |

The app reads `Build.FINGERPRINT` (falling back to `VERSION.INCREMENTAL`,
`DISPLAY`, `ID`) and picks the payload whose build string appears there. A build
that is not in the table is refused, with a link to report it. The
"Allow untested firmware/build" checkbox skips the untested warning, never the
firmware match.

## Layout

- `app/` — Android module (package `dev.ionstack.root`, minSdk 31)
- `app/src/main/assets/payload/<BUILD>/` — exploit library + root helper per firmware
- `app/src/main/assets/payload/common/` — shared KernelSU late-load files
- `app/src/main/java/dev/ionstack/root/Targets.java` — firmware table
- `sync-payloads.sh` — rebuild every target on a build host and refresh the assets
- `build.sh` — debug APK build

The exploit stage (`cve-exp32` / `cve-exp64`) is embedded inside each payload
library, so only the library and the root helper are staged to
`/data/local/tmp`.

## Build

App:

```bash
./build.sh
# or
./gradlew :app:assembleDebug
```

Payloads for all targets (needs an Android NDK on an x86-64 host; this project
uses `ssh segfault` with NDK at `~/work/android-ndk-r27c` and the exploit
checkout at `~/work/IonStack`):

```bash
./sync-payloads.sh              # build all targets on segfault, refresh assets
./sync-payloads.sh --replace-verified   # also overwrite the verified ZH4 pair
```

`S908EXXSEGZH4` keeps the binaries from the device-verified run; a fresh build
of the current source is not byte-identical to that pair, so it is only replaced
on request (and should then be retested).

## Use

1. Install the APK and open it. The gate line shows the matched build and whether
   it is verified.
2. Start Shizuku as the shell user, then tap **Grant Shizuku**.
3. Tap **Run exploit**. Payload staging is automatic. The race is timing
   sensitive: reboot first, close background apps, keep the screen unlocked.
4. On success the root daemon listens at `/data/local/tmp/temp_su.sock`; use
   **Run as root** with e.g. `id`.
5. Optional **Load KernelSU** late-loads `kernelsu.ko`. This is only validated on
   S908EXXSEGZH4; other builds get a separate warning first.

## Notes and limitations

- Run only on devices you own and are authorized to test. A failed race stage can
  panic the kernel.
- KernelSU late-load is volatile (until reboot) and its patch table is empty, so
  KASLR-dependent patching is a no-op; treat it as best effort.
- Changing the app id from the old `dev.ionstack.s908e` means a fresh install and
  a new Shizuku grant; uninstall the old package if both are present.
- Payload binaries are Apache-2.0 code from the IonStack project; see `LICENSE`
  and `NOTICE`. The app wrapper is not affiliated with Samsung.
