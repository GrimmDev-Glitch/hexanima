#!/bin/bash
# Dev APK (package com.hexanima.npu.dev, installs next to the release): app/hexanima-dev.apk
# Needs Docker running, python3, and the Android SDK at ~/anima-npu/sdk/android (or ANDROID_SDK_ROOT). Run from WSL: bash tools/dev_build.sh
set -e
cd "$(dirname "$0")/.."
BUILD_DEV=1 NOINSTALL=1 bash app/build.sh 2>&1 | tail -6
ls -l app/hexanima-dev.apk
