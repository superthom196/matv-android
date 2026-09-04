#!/bin/zsh
# Build (and optionally install) MATV using Android Studio's bundled JDK.
# Usage: ./build.sh                     -> assembleRelease (minified, debug-signed)
#        TV=<ip:port> ./build.sh install -> assembleRelease + install, AOT-compile and launch on the TV
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
./gradlew assembleRelease
if [[ "$1" == "install" ]]; then
  if [[ -z "$TV" ]]; then
    echo "Set TV=<ip:port> to your Android TV's adb address, e.g. TV=192.168.1.50:5555 ./build.sh install" >&2
    exit 1
  fi
  ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
  "$ADB" connect "$TV"
  "$ADB" -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
  "$ADB" -s "$TV" shell cmd package compile -m speed -f io.github.superthom196.matv
  "$ADB" -s "$TV" shell am start -n io.github.superthom196.matv/.MainActivity
fi
