#!/bin/bash
# Signed release APK: app/hexanima-release.apk. Asks for the keystore password (never stored).
# Keystore: %USERPROFILE%\.hexanima-signing\release.keystore (alias hexanima), or RELEASE_KEYSTORE. Started by tools/release_build.ps1.
set -e
cd "$(dirname "$0")/.."
WINHOME=$(wslpath "$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")
KS=${RELEASE_KEYSTORE:-$WINHOME/.hexanima-signing/release.keystore}
[ -f "$KS" ] || { echo "No keystore at $KS"; exit 2; }
read -rsp "Clave del keystore: " P < /dev/tty; echo
rm -f app/hexanima-release.apk
echo "Compilando (unos minutos)..."
BUILD_TYPE=release NOINSTALL=1 RELEASE_KEYSTORE="$KS" RELEASE_KEY_ALIAS=hexanima RELEASE_KEYSTORE_PASSWORD="$P" bash app/build.sh
