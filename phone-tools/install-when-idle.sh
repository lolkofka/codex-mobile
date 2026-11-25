#!/system/bin/sh
set -eu
umask 077
BASE=/data/adb/avr-codex-copy
OUT="$BASE/upgrade-0.6.6"
export CLASSPATH="$BASE/tools/phone-tools.dex"
android_command(){
    # Android shell Binder commands cannot receive our private /data/adb log FDs.
    # Relay stdin/stdout/stderr through allowed descriptors without changing SELinux.
    command_status="$OUT/.android-command-$$.status"
    (
        set +e
        "$@" </dev/null
        command_result=$?
        printf '%s\n' "$command_result" > "$command_status"
        exit 0
    ) 2>&1 | cat
    [ -f "$command_status" ] || return 125
    read -r command_result < "$command_status"
    rm -f "$command_status"
    return "$command_result"
}
fail(){ printf '{"status":"failed","stage":"%s"}\n' "$stage" > "$OUT/result.json"; }
stage=waiting
trap fail EXIT
/system/bin/app_process /system/bin UpgradeWaiter "$1" "$2"
android_command pm path local.avr.codex > "$OUT/original-before.txt" || true
original_before=false
if grep -q '^package:' "$OUT/original-before.txt"; then original_before=true; fi
foreground_copy=false
android_command dumpsys activity activities > "$OUT/foreground-before.txt"
if grep -Eq '(mResumedActivity|topResumedActivity).*local.avr.codex.copy/' "$OUT/foreground-before.txt"; then foreground_copy=true; fi
stage=installing
printf '{"status":"installing","version":"0.6.6-copy"}\n' > "$OUT/result.json"
STAGED=/data/local/tmp/codex-copy-tools/Codex-Mobile-Copy-0.6.6.apk
cp "$OUT/Codex-Mobile-Copy-0.6.6.apk" "$STAGED"
chmod 644 "$STAGED"
prepared_session=""
if [ -s "$OUT/install-session.id" ]; then
    read -r prepared_session < "$OUT/install-session.id"
    case "$prepared_session" in ''|*[!0-9]*) exit 1;; esac
    current_digest=$(sha256sum "$STAGED" | awk '{print $1}')
    prepared_digest=$(cat "$OUT/install-session.sha256" 2>/dev/null || true)
    if [ "$current_digest" != "$prepared_digest" ]; then
        android_command pm install-abandon "$prepared_session" || true
        prepared_session=""
    fi
fi
if [ -n "$prepared_session" ]; then
    android_command pm install-commit "$prepared_session"
else
    android_command pm install -r "$STAGED"
fi
rm -f "$OUT/install-session.id" "$OUT/install-session.sha256"
android_command dumpsys package local.avr.codex.copy > "$OUT/package-after.txt"
grep -q 'versionCode=13 ' "$OUT/package-after.txt" || exit 1
stage=starting
# Start the service without opening an activity on the owner's primary screen.
android_command am start-foreground-service --user 0 -n local.avr.codex.copy/.AgentService
n=0
while true; do
    service_pid=$(pidof local.avr.codex.copy || true)
    case "$service_pid" in ''|*[!0-9]*) ;; *)
        if [ -f /data/user/0/local.avr.codex.copy/files/phone-access/heartbeat.json ] && grep -Eq "\"pid\":$service_pid[,}]" /data/user/0/local.avr.codex.copy/files/phone-access/heartbeat.json; then break;fi
    ;; esac
    n=$((n+1));[ "$n" -lt 80 ] || exit 1;sleep 0.5
done
stage=verified
android_command pm path local.avr.codex.copy > "$OUT/new-apk-path.txt"
read -r package_path < "$OUT/new-apk-path.txt"
package_path=${package_path#package:}
new_core="${package_path%/*}/lib/arm64/libxray.so"
"$new_core" version > "$OUT/xray-version.txt"
grep -q 'Xray 26.11.9 ' "$OUT/xray-version.txt" || exit 1
"$BASE/tools/bin/codex-servers" list > "$OUT/servers-check.json"
grep -q '"ok":true' "$OUT/servers-check.json" || exit 1
android_command pm path local.avr.codex > "$OUT/original-after.txt" || true
original_after=false
if grep -q '^package:' "$OUT/original-after.txt"; then original_after=true; fi
if [ "$original_before" = true ] && [ "$original_after" != true ]; then exit 1; fi
printf '{"status":"installed","version":"0.6.6-copy","versionCode":13,"newServiceHeartbeatVerified":true,"serversBrokerVerified":true,"originalAppWasPresent":%s,"originalAppRetained":%s,"certificateActive":false,"xrayExecutableVerified":true,"completed":%s000}\n' "$original_before" "$original_after" "$(date +%s)" > "$OUT/result.json"
trap - EXIT
if [ "$foreground_copy" = true ]; then
    android_command cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER local.avr.codex.copy > "$OUT/launcher.txt"
    component=$(tail -n 1 "$OUT/launcher.txt")
    case "$component" in local.avr.codex.copy/*) android_command am start -n "$component" || true;; esac
fi
