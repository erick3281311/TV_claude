#!/usr/bin/env bash
# Compila el APK del launcher. Requiere JDK 17 + Android SDK (ANDROID_HOME) + gradle (o gradlew).
cd "$(dirname "$0")"
if [ -x ./gradlew ]; then ./gradlew assembleDebug
elif command -v gradle >/dev/null; then gradle assembleDebug
else echo "Instala gradle (brew install gradle) o abre esta carpeta en Android Studio."; exit 1; fi
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
