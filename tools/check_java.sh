#!/bin/bash
# Quick compile check of the app's Java (no APK): prints the errors, or nothing. Run from WSL: bash tools/check_java.sh
cd "$(dirname "$0")/../app"
SDK=${ANDROID_SDK_ROOT:-$HOME/anima-npu/sdk/android}
docker run --rm -v "$SDK:/sdk" -v "$PWD:/app" eclipse-temurin:17-jdk bash -c 'cd /app && mkdir -p /tmp/c && javac -encoding UTF-8 --release 11 -Xmaxerrs 400 -classpath /sdk/platforms/android-34/android.jar -d /tmp/c src/com/hexanima/npu/*.java 2>&1 | grep -E "error:|symbol:|location:"'
echo "check done"
