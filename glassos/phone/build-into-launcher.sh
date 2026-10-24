#!/usr/bin/env bash
# Build the phone app and bundle it into the launcher, which serves it to phones at http://<tv>:8642/app.apk.
# Run this before launcher/build-and-install.sh whenever phone/ changes. JAVA_HOME as for the launcher.
set -euo pipefail
cd "$(dirname "$0")"
[ -f local.properties ] || echo "sdk.dir=${ANDROID_HOME:-$HOME/Library/Android/sdk}" > local.properties
./gradlew assembleRelease -q --console=plain
cp app/build/outputs/apk/release/app-release.apk ../launcher/app/src/main/assets/remote/app.apk
echo "phone app bundled: $(wc -c < ../launcher/app/src/main/assets/remote/app.apk) bytes"
