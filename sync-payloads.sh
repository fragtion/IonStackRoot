#!/usr/bin/env bash
# Rebuild the IonStack payloads for every target and refresh app assets.
#
#   ./sync-payloads.sh                     # build on `segfault`, copy all targets
#   HOST=other ./sync-payloads.sh          # use another ssh host
#   ./sync-payloads.sh --replace-verified  # also overwrite the verified ZH4 pair
#
# The build host needs an Android NDK and the exploit checkout; the local
# src/ tree is rsynced there first so binaries match this source tree.
#
#   HOST        ssh host with the NDK and make/clang     (default segfault)
#   REMOTE_DIR  exploit checkout on the host             (default work/IonStack)
#   NDK_PATH    NDK root on the host                     (default $HOME/work/android-ndk-r27c)
#   LOCAL_REPO  local exploit checkout                   (default ../IonStack-S22U)
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$APP_DIR/app/src/main/assets/payload"
HOST="${HOST:-segfault}"
REMOTE_DIR="${REMOTE_DIR:-work/IonStack}"
NDK_PATH="${NDK_PATH:-\$HOME/work/android-ndk-r27c}"
LOCAL_REPO="${LOCAL_REPO:-$(dirname "$APP_DIR")/IonStack-S22U}"
VERIFIED=S908EXXSEGZH4
REPLACE_VERIFIED=0

for arg in "$@"; do
  case "$arg" in
    --replace-verified) REPLACE_VERIFIED=1 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) echo "unexpected argument: $arg" >&2; exit 2 ;;
  esac
done

if [ ! -d "$LOCAL_REPO/src/targets" ]; then
  echo "no exploit checkout at $LOCAL_REPO (set LOCAL_REPO)" >&2
  exit 1
fi

# The shared ssh host sometimes interleaves its "waiting for resources" dots
# with the first bytes of a connection, which corrupts rsync's protocol.
retry_rsync() {
  local attempt
  for attempt in 1 2 3 4 5; do
    if rsync -a "$@"; then
      return 0
    fi
    echo "  retrying rsync ($attempt/5)" >&2
    sleep 2
  done
  return 1
}

echo "[*] syncing sources to $HOST:~/$REMOTE_DIR"
retry_rsync \
  --include '*/' --include 'src/***' --include 'Makefile' --exclude '*' \
  "$LOCAL_REPO/" "$HOST:$REMOTE_DIR/"

echo "[*] building every target"
# The ssh host prints a login banner, so keep only plausible target names.
TARGETS=$(ssh "$HOST" "cd $REMOTE_DIR && ls -d src/targets/*/ | xargs -n1 basename" \
  2>/dev/null | grep -E '^[A-Z0-9]{8,16}$' | sort)
if [ -z "$TARGETS" ]; then
  echo "could not list targets on $HOST" >&2
  exit 1
fi
FAILED=""
for target in $TARGETS; do
  built=0
  for attempt in 1 2 3; do
    out=$(ssh "$HOST" "cd $REMOTE_DIR && export ANDROID_NDK_HOME=$NDK_PATH && \
        make PROJECT=$target preload root-helper >/tmp/build-$target.log 2>&1 \
        && echo BUILD_OK" 2>/dev/null) || true
    case "$out" in *BUILD_OK*) built=1; break ;; esac
    sleep 2
  done
  if [ "$built" = 1 ]; then
    printf '  ok   %s\n' "$target"
  else
    printf '  FAIL %s\n' "$target"
    FAILED="$FAILED $target"
  fi
done
if [ -n "$FAILED" ]; then
  echo "build failed for:$FAILED (logs in /tmp/build-<target>.log on $HOST)" >&2
  exit 1
fi

echo "[*] copying payloads into assets"
mkdir -p "$ASSETS"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
retry_rsync \
  --include '*/' --include 'cve-2026-43499' --include 'cve-2026-43499-root' \
  --exclude '*' \
  "$HOST:$REMOTE_DIR/build/" "$TMP/"
for target in $TARGETS; do
  if [ "$target" = "$VERIFIED" ] && [ "$REPLACE_VERIFIED" = 0 ]; then
    echo "  keep $VERIFIED (device-verified pair; --replace-verified to overwrite)"
    continue
  fi
  mkdir -p "$ASSETS/$target"
  cp "$TMP/$target/bin/cve-2026-43499" \
     "$TMP/$target/bin/cve-2026-43499-root" "$ASSETS/$target/"
done

missing=0
for target in $TARGETS; do
  for name in cve-2026-43499 cve-2026-43499-root; do
    if [ ! -f "$ASSETS/$target/$name" ]; then
      echo "missing $target/$name" >&2
      missing=1
    fi
  done
done
[ "$missing" = 0 ] || exit 1

echo "[*] $(ls -d "$ASSETS"/*/ | wc -l) payload directories staged; hashes:"
sha256sum "$ASSETS"/*/cve-2026-43499 | sed 's#^#  #'
