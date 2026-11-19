#!/bin/bash
# Copies the QAIRT 2.28 runtime (V73), sdxlrun and the built contexts + small files to the phone test folder /data/local/tmp/sdxl.
# Run from WSL; adb is the Windows adb.exe, so every path is handed over as a Windows path (wslpath -w).
# usage: ./phone_setup.sh [libs] [ctx FILE...]
set -e
Q=$HOME/anima-npu/sdk/qairt/2.28.0.241029/lib
PH=/data/local/tmp/sdxl; cd "$(dirname "$0")"
w() { for f in "$@"; do wslpath -w "$f"; done; }
adb.exe shell mkdir -p $PH
if [ "$1" = libs ]; then
  adb.exe push $(w $Q/aarch64-android/libQnnHtp.so $Q/aarch64-android/libQnnSystem.so $Q/aarch64-android/libQnnHtpV73Stub.so $Q/hexagon-v73/unsigned/libQnnHtpV73Skel.so sdxlrun) $PH/
  adb.exe shell chmod +x $PH/sdxlrun; shift
fi
if [ "$1" = ctx ]; then shift; for f in "$@"; do adb.exe push "$(wslpath -w "$f")" $PH/; done; fi
adb.exe shell ls -la $PH
