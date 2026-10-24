#!/usr/bin/env bash
# Build the TV Remote phone app and the launcher, and install the launcher on the TV over Wi-Fi.
# Uses this repo's tools/ (python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt).
#   TV_HOST=192.168.1.6 glassos/install.sh
# The first time, it also hands Home over from the TV's own launcher, safely: the new launcher is started and
# checked before the stock one is disabled, and if Home does not end up on it, the stock launcher is put back.
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
TV() { "$PY" -I "$ROOT/tools/tv.py" "$@"; }
HOME_Q='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
SDK=$(TV 'getprop ro.build.version.sdk' | tr -dc '0-9')
if [ -n "$SDK" ] && [ "$SDK" -lt 26 ]; then
  echo "ERROR: this TV runs Android SDK $SDK; the launcher needs 26 (Android 8.0) or newer. Nothing was changed." >&2; exit 1
fi
HOME_BEFORE=$(TV "$HOME_Q" | tail -n 1 | tr -d '\r')

phone/build-into-launcher.sh                                    # the phone app, served by the TV at /app.apk
(cd launcher && ./gradlew assembleRelease -q --console=plain)
"$PY" -I "$ROOT/tools/b64push.py" launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/glassos.apk
OUT=$("$PY" -I "$ROOT/tools/tv.py" 'pm install -r /data/local/tmp/glassos.apk; rm -f /data/local/tmp/glassos.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: the TV did not take the update; the old launcher is still in place." >&2; exit 1 ;; esac
# Compiled ahead of time for a quick start; the overlay for the pairing code and for opening apps from the
# phone; the Wi-Fi name in Control Center; the Bluetooth tile. Home itself is not touched.
"$PY" -I "$ROOT/tools/tv.py" 'cmd package compile -m speed -f com.gathin.tvhome >/dev/null; appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow; pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION; pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS; echo "Installed. The phone remote is back in a few seconds."'

# ---- first install: hand Home over ------------------------------------------------------------------
case "$HOME_BEFORE" in com.gathin.tvhome/*) exit 0 ;; esac
STOCK=${HOME_BEFORE%%/*}
KNOWN="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
case " $KNOWN " in
  *" $STOCK "*) ;;
  *) echo "Home is '$HOME_BEFORE', not a stock launcher this script knows. com.google.android.tungsten.setupwraith is" >&2
     echo "Google TV's setup wizard: debloat it first (see examples/). Otherwise name it: STOCK_LAUNCHER=<package> $0" >&2
     [ -n "${STOCK_LAUNCHER:-}" ] || exit 1
     STOCK=$STOCK_LAUNCHER ;;
esac
echo "Handing Home over from $STOCK"
START=$(TV 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity')
case "$START" in *"Status: ok"*) ;; *) echo "$START"; echo "ERROR: the new launcher did not start; $STOCK was left as Home." >&2; exit 1 ;; esac
TV "pm disable-user --user 0 $STOCK; cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" >/dev/null
NOW=$(TV "$HOME_Q" | tail -n 1 | tr -d '\r')
case "$NOW" in
  com.gathin.tvhome/*) echo "Home is now the new launcher. Undo: tools/restore.sh <your debloat list> $STOCK" ;;
  *) echo "ERROR: Home went to '$NOW'; putting $STOCK back." >&2
     TV "pm enable --user 0 $STOCK; cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)" >/dev/null
     exit 1 ;;
esac
