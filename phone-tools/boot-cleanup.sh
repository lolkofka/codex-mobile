#!/system/bin/sh
# Cleanup only. This never starts background apps, a proxy, or packet capture.
BASE=/data/adb/avr-codex-copy/tools
[ ! -x "$BASE/https-cleanup" ] || "$BASE/https-cleanup" >/dev/null 2>&1
