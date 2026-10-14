#!/bin/bash
# Builds LiberatedFrame without Gradle: aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner.
#
# Requirements:
#   - JDK 17+ on PATH
#   - Android SDK with build-tools 35.0.0+ and platforms;android-34
#     (e.g. `sdkmanager "build-tools;35.0.0" "platforms;android-34"`)
#   - ANDROID_HOME pointing at the SDK (defaults to ~/Library/Android/sdk)
#
# Signing: a keystore is generated on first build (release.keystore, never commit it).
# Updating an installed app requires signing with the SAME keystore — back it up.
set -e
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$ANDROID_HOME/build-tools/35.0.0"
PLAT="$ANDROID_HOME/platforms/android-34/android.jar"
KS_PASS="${KEYSTORE_PASS:-albumframe}"
# Signing key lives OUTSIDE the repo by default, so it can never be committed or
# bundled into a fork. Override with KEYSTORE=/path/to/release.keystore.
KEYSTORE="${KEYSTORE:-$HOME/.liberatedframe/release.keystore}"

[ -x "$BT/aapt2" ] || { echo "error: build-tools 35.0.0 not found under $ANDROID_HOME"; exit 1; }
[ -f "$PLAT" ] || { echo "error: platforms;android-34 not found under $ANDROID_HOME"; exit 1; }

rm -rf obj obj.jar dexout out; mkdir -p obj dexout out

RES_ARG=""
if [ -d res ]; then
  echo "== aapt2 compile resources =="
  "$BT/aapt2" compile --dir res -o out/res.zip
  RES_ARG="out/res.zip"
fi

echo "== aapt2 link =="
"$BT/aapt2" link -o out/base.apk --manifest AndroidManifest.xml -I "$PLAT" \
  $RES_ARG --min-sdk-version 23 --target-sdk-version 23

echo "== javac =="
javac --release 8 -Xlint:-options -cp "$PLAT" -d obj $(find src -name '*.java')

echo "== d8 =="
jar cf obj.jar -C obj .
"$BT/d8" --release --min-api 23 --lib "$PLAT" --output dexout obj.jar

echo "== package =="
cp out/base.apk out/unsigned.apk
cd dexout && zip -q ../out/unsigned.apk classes.dex && cd ..

echo "== zipalign + sign =="
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk
if [ ! -f "$KEYSTORE" ]; then
  echo "== generating signing keystore at $KEYSTORE (KEEP IT SAFE, NEVER COMMIT IT) =="
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias albumframe \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -keyalg RSA -validity 10950 \
    -dname "CN=LiberatedFrame" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --out out/liberatedframe.apk out/aligned.apk
rm -f out/base.apk out/unsigned.apk out/aligned.apk out/liberatedframe.apk.idsig

echo "== done =="
ls -la out/liberatedframe.apk
