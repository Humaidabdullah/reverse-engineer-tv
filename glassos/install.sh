#!/usr/bin/env bash
# Build the TV Remote phone app and the launcher, and install the launcher on the TV over Wi-Fi.
# Uses this repo's tools/ (python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt).
#   TV_HOST=192.168.1.6 glassos/install.sh
# An update installs in place: Home, the phone remote's pairings and presets, and the wallpapers all stay.
# The APKs are signed with this computer's debug key; from another computer the TV refuses the update
# until the old launcher is uninstalled, so build where it was first built.
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.6 $0}"
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
PY="$ROOT/tools/venv/bin/python"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"   # Gradle 8.9 runs on JDK 17 or 21
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f launcher/local.properties ] || echo "sdk.dir=$ANDROID_HOME" > launcher/local.properties

phone/build-into-launcher.sh                                    # the phone app, served by the TV at /app.apk
(cd launcher && ./gradlew assembleRelease -q --console=plain)
"$PY" -I "$ROOT/tools/b64push.py" launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/glassos.apk
OUT=$("$PY" -I "$ROOT/tools/tv.py" 'pm install -r /data/local/tmp/glassos.apk; rm -f /data/local/tmp/glassos.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: the TV did not take the update; the old launcher is still in place." >&2; exit 1 ;; esac
# Compiled ahead of time for a quick start; the overlay for the pairing code and for opening apps from the
# phone; the Wi-Fi name in Control Center; the Bluetooth tile. Home itself is not touched.
"$PY" -I "$ROOT/tools/tv.py" 'cmd package compile -m speed -f com.gathin.tvhome >/dev/null; appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow; pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION; pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS; echo "Installed. The phone remote is back in a few seconds."'
