#!/usr/bin/env bash
# Refresh app/src/main/assets/payload from the IonStack-S22U exploit build.
#
#   ./sync-payloads.sh                 # copy prebuilt s22/ binaries
#   ./sync-payloads.sh --build         # rebuild for S908EXXSEGZH4, then copy
#   ./sync-payloads.sh /path/to/repo   # use another exploit checkout
#
# --build needs an Android NDK the host can execute. The s22/ directory is the
# already-built S908EXXSEGZH4 payload set used by the device run.
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$APP_DIR/app/src/main/assets/payload"
ROOT_DIR="$(dirname "$APP_DIR")"
SRC="${ROOT_DIR}/IonStack-S22U"
BUILD=0

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *) SRC="$arg" ;;
  esac
done

TARGET=S908EXXSEGZH4
OUT="$SRC/build/$TARGET/bin"

if [ "$BUILD" = 1 ]; then
  : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an executable NDK r27+}"
  make -C "$SRC" PROJECT="$TARGET" preload root-helper
fi

mkdir -p "$ASSETS"
if [ -d "$SRC/s22" ]; then
  SRC_DIR="$SRC/s22"
else
  SRC_DIR="$OUT"
fi

for name in cve-2026-43499 cve-2026-43499-root cve-exp32; do
  if [ ! -f "$SRC_DIR/$name" ]; then
    echo "missing $SRC_DIR/$name; build with --build or pass the exploit repo" >&2
    exit 1
  fi
  cp "$SRC_DIR/$name" "$ASSETS/$name"
  sha256sum "$ASSETS/$name"
done

# KernelSU artifacts only exist in the prebuilt s22/ set.
if [ -d "$SRC/s22" ]; then
  for name in kernelsu.ko patch_table.txt; do
    if [ -f "$SRC/s22/$name" ]; then
      cp "$SRC/s22/$name" "$ASSETS/$name"
      sha256sum "$ASSETS/$name"
    fi
  done
fi

echo "staged payloads in $ASSETS"
