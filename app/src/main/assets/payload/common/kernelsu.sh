#!/system/bin/sh
#
# KernelSU late-load for SM-S908E / S908EXXSEGZH4.
#
# Derived from s22/root.sh. The exploit step is intentionally omitted: this
# script is meant to run after /data/local/tmp/cve-2026-43499-root is already
# serving root. Requires:
#   /data/local/tmp/cve-2026-43499-root  root helper (client/daemon)
#   /data/local/tmp/kernelsu.ko          LKM
#   /data/local/tmp/patch_table.txt      KASLR patch table (may be empty)
#
DIR=/data/local/tmp
HELPER=$DIR/cve-2026-43499-root
KO=$DIR/kernelsu.ko
TABLE=$DIR/patch_table.txt
LOG=$DIR/kernelsu_log.txt

log() { echo "[*] $*"; echo "[*] $*" >> "$LOG"; }
err() { echo "[!] $*" >&2; echo "[!] $*" >> "$LOG"; }
die() { err "$*"; exit 1; }
root_exec() { "$HELPER" -c "$1" 2>&1; }

for f in "$HELPER" "$KO" "$TABLE"; do
    [ -e "$f" ] || die "missing: $f"
done

log "=== KernelSU late-load ==="
OUT=$(root_exec 'id')
case "$OUT" in
    *uid=0*) log "root: $OUT" ;;
    *) die "root daemon not ready: ${OUT:-no output}" ;;
esac

root_exec 'echo 0 > /proc/sys/kernel/panic_on_oops' >/dev/null 2>&1
root_exec 'echo 0 > /proc/sys/kernel/panic' >/dev/null 2>&1

ENTRIES=$(grep -vcE '^[[:space:]]*(#|$)' "$TABLE" 2>/dev/null)
[ -n "$ENTRIES" ] || ENTRIES=0

if [ "$ENTRIES" -gt 0 ]; then
    log "patching .ko with KASLR offsets ($ENTRIES entries)"
    root_exec 'echo 0 > /proc/sys/kernel/kptr_restrict' >/dev/null 2>&1

    TEXT_HEX=$(root_exec 'grep -m1 " T _text$" /proc/kallsyms' | cut -d' ' -f1 | tr -d '\r\n')
    [ ${#TEXT_HEX} -eq 16 ] || die "_text not found: '${TEXT_HEX}'"
    log "_text = ${TEXT_HEX}"

    T3=$((0x${TEXT_HEX:0:4}))
    T2=$((0x${TEXT_HEX:4:4}))
    T1=$((0x${TEXT_HEX:8:4}))
    T0=$((0x${TEXT_HEX:12:4}))

    COUNT=0
    while IFS=' ' read -r FILE_OFF SYM_OFF SYM_NAME; do
        case "$FILE_OFF" in '#'*|'') continue ;; esac
        PAD="0000000000000000${SYM_OFF}"
        S="${PAD#${PAD%????????????????}}"
        O3=$((0x${S:0:4})); O2=$((0x${S:4:4}))
        O1=$((0x${S:8:4})); O0=$((0x${S:12:4}))
        R0=$((T0 + O0)); R1=$((T1 + O1 + (R0 >> 16)))
        R2=$((T2 + O2 + (R1 >> 16))); R3=$((T3 + O3 + (R2 >> 16)))
        R0=$((R0 & 0xFFFF)); R1=$((R1 & 0xFFFF))
        R2=$((R2 & 0xFFFF)); R3=$((R3 & 0xFFFF))
        HX=$(printf '%04x%04x%04x%04x' $R3 $R2 $R1 $R0)
        SEEK=$((0x$FILE_OFF))
        printf "\x${HX:14:2}\x${HX:12:2}\x${HX:10:2}\x${HX:8:2}\x${HX:6:2}\x${HX:4:2}\x${HX:2:2}\x${HX:0:2}" | \
            dd of="$KO" bs=1 seek=$SEEK conv=notrunc 2>/dev/null || die "patch failed at $FILE_OFF"
        COUNT=$((COUNT + 1))
    done < "$TABLE"
    log "patched ${COUNT} symbols"
else
    log "patch table has no entries; skipping KASLR patch"
fi

log "loading kernelsu.ko"
INSMOD_OUT=$(root_exec "insmod $KO" 2>&1)
[ -n "$INSMOD_OUT" ] && log "insmod: $INSMOD_OUT"
sleep 2

MOD=$(root_exec 'grep "^kernelsu " /proc/modules')
case "$MOD" in
    kernelsu*) log "KernelSU loaded: $MOD"; log "=== DONE ==="; exit 0 ;;
esac

# Some late-load builds disappear from /proc/modules while still being live.
if root_exec 'test -d /sys/module/kernelsu && echo KSU_PRESENT' | grep -q KSU_PRESENT; then
    log "KernelSU present via /sys/module/kernelsu"
    log "=== DONE ==="
    exit 0
fi

die "module not loaded: ${MOD:-no output}"
