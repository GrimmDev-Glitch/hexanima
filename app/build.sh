#!/bin/bash
# Build HexAnima without Gradle. Debug remains the local default; release builds
# are non-debuggable and require a caller-supplied signing key outside the repo.
set -euo pipefail
cd "$(dirname "$0")"

BUILD_TYPE=${BUILD_TYPE:-debug}
case "$BUILD_TYPE" in
  debug) APK_NAME=hexanima.apk; DEBUG_FLAG=--debug-mode ;;
  release) APK_NAME=hexanima-release.apk; DEBUG_FLAG= ;;
  *) echo "BUILD_TYPE must be debug or release" >&2; exit 2 ;;
esac
if [[ "${BUILD_DEV:-0}" == 1 ]]; then APK_NAME=hexanima-dev.apk; fi

cp ../phone/anima.sh assets/anima.sh
cp ../phone/moss.sh assets/moss.sh
cp ../phone/qwen.sh assets/qwen.sh
SDK=${ANDROID_SDK_ROOT:-$HOME/anima-npu/sdk/android}
[[ -d "$SDK/build-tools/34.0.0" ]] || { echo "Android SDK not found: $SDK" >&2; exit 2; }

DOCKER_ARGS=(run --rm -v "$SDK:/sdk" -v "$PWD:/app" -e "BUILD_TYPE=$BUILD_TYPE" -e "APK_NAME=$APK_NAME" -e "DEBUG_FLAG=$DEBUG_FLAG")
DOCKER_ARGS+=( -e "BUILD_DEV=${BUILD_DEV:-0}" )
if [[ "$BUILD_TYPE" == release ]]; then
  : "${RELEASE_KEYSTORE:?Set RELEASE_KEYSTORE to a keystore path outside the repo}"
  : "${RELEASE_KEY_ALIAS:?Set RELEASE_KEY_ALIAS}"
  : "${RELEASE_KEYSTORE_PASSWORD:?Set RELEASE_KEYSTORE_PASSWORD}"
  RELEASE_KEY_PASSWORD=${RELEASE_KEY_PASSWORD:-$RELEASE_KEYSTORE_PASSWORD}
  [[ -f "$RELEASE_KEYSTORE" ]] || { echo "Release keystore not found: $RELEASE_KEYSTORE" >&2; exit 2; }
  DOCKER_ARGS+=( -v "$RELEASE_KEYSTORE:/signing/release.keystore:ro"
    -e "RELEASE_KEY_ALIAS=$RELEASE_KEY_ALIAS"
    -e "RELEASE_KEYSTORE_PASSWORD=$RELEASE_KEYSTORE_PASSWORD"
    -e "RELEASE_KEY_PASSWORD=$RELEASE_KEY_PASSWORD" )
fi

docker "${DOCKER_ARGS[@]}" eclipse-temurin:17-jdk bash -c '
set -euo pipefail
BT=/sdk/build-tools/34.0.0; AJ=/sdk/platforms/android-34/android.jar; cd /app
rm -rf out; mkdir -p out/cls
cp AndroidManifest.xml out/AndroidManifest.xml
if [ "$BUILD_DEV" = 1 ]; then
  sed -i -e "s/package=\"com.hexanima.npu\"/package=\"com.hexanima.npu.dev\"/" -e "s/android:name=\"\./android:name=\"com.hexanima.npu./g" -e "s/com.hexanima.npu.gallery/com.hexanima.npu.dev.gallery/g" -e "s/android:label=\"HexAnima\"/android:label=\"HexAnima Dev\"/" out/AndroidManifest.xml
fi
$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -o out/base.apk -I "$AJ" -A assets --manifest out/AndroidManifest.xml --min-sdk-version 29 --target-sdk-version 34 $DEBUG_FLAG out/res.zip
javac -encoding UTF-8 --release 11 -classpath "$AJ" -d out/cls src/com/hexanima/npu/*.java
$BT/d8 --lib "$AJ" --min-api 29 --output out out/cls/com/hexanima/npu/*.class
cd out && cp base.apk unsigned.apk && $BT/aapt add unsigned.apk classes.dex >/dev/null && cd ..
if [ -d jnilibs ]; then (cd jnilibs && $BT/aapt add ../out/unsigned.apk $(find lib -type f) >/dev/null); fi
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
if [ "$BUILD_TYPE" = release ]; then
  $BT/apksigner sign --ks /signing/release.keystore --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
    --out "$APK_NAME" out/aligned.apk
else
  [ -f /sdk/debug.keystore ] || keytool -genkeypair -keystore /sdk/debug.keystore -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname CN=anima
  $BT/apksigner sign --ks /sdk/debug.keystore --ks-pass pass:android --out "$APK_NAME" out/aligned.apk
fi
rm -rf out; ls -l "$APK_NAME"
'

if [[ -z "${NOINSTALL:-}" && "$BUILD_TYPE" == debug ]]; then
  adb install -r "$APK_NAME"
elif [[ -z "${NOINSTALL:-}" && "$BUILD_TYPE" == release ]]; then
  echo "Release APK built but not installed automatically: $APK_NAME"
fi
