#!/usr/bin/env bash
set -e
SDK="$1"; BT="$SDK/build-tools/34.0.0"; JAR="$SDK/platforms/android-34/android.jar"
mkdir -p out classes

javac -source 11 -target 11 -classpath "$JAR" -d classes $(find src -name '*.java')
"$BT/d8" --release --min-api 29 --lib "$JAR" $(find classes -name '*.class') --output out/
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 34
cd out && zip -qj base.apk classes.dex && cd ..
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
keytool -genkeypair -keystore out/ks.jks -alias bridge -storepass android -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=AudioBridge" 2>/dev/null
"$BT/apksigner" sign --ks out/ks.jks --ks-pass pass:android \
    --out out/AudioBridge.apk out/aligned.apk
echo "OK -> out/AudioBridge.apk"
