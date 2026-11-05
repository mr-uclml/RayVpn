#!/usr/bin/env bash
# Builds RayVPN.apk without Gradle, using only the plain Android toolchain.
#
#   aapt2  compile/link resources      (build-tools)
#   javac  compile the Java sources    (JDK 8 preferred, android.jar as bootclasspath)
#   d8     classes -> classes.dex      (build-tools / r8)
#   node   v2 signing                  (tools/v2sign.mjs, no apksigner needed)
#
# Environment (all optional, defaults in brackets):
#   JAVA        java binary            [java]
#   JAVAC_JAR   tools.jar of a JDK 8   [] (use it when `javac` is not installed)
#   AAPT2       aapt2 binary           [aapt2]
#   ANDROID_JAR platform android.jar   [$ANDROID_HOME/platforms/android-34/android.jar]
#   D8_JAR      d8/r8 jar              []
#   KEYSTORE / STORE_PASS / KEY_ALIAS / KEY_PASS   [app/rayvpn-release.jks, rayvpn-local, rayvpn, rayvpn-local]
#
# The d8 that ships inside build-tools <= 30 (r8 0.1.x) refuses class files that
# carry a MethodParameters attribute (gomobile writes them). The script rewrites
# that attribute name before dexing; both attributes are cosmetic, ART ignores
# them, so the produced dex is identical in behaviour.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main"
OUT="${OUT:-$ROOT/build}"
JAVA="${JAVA:-java}"
AAPT2="${AAPT2:-aapt2}"
ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-}/platforms/android-34/android.jar}"
D8_JAR="${D8_JAR:-}"
JAVAC_JAR="${JAVAC_JAR:-}"
KEYSTORE="${KEYSTORE:-$ROOT/app/rayvpn-release.jks}"
STORE_PASS="${STORE_PASS:-rayvpn-local}"
KEY_ALIAS="${KEY_ALIAS:-rayvpn}"
KEY_PASS="${KEY_PASS:-rayvpn-local}"
VERSION_CODE="${VERSION_CODE:-10}"
VERSION_NAME="${VERSION_NAME:-1.8.0}"
APK_OUT="${APK_OUT:-$ROOT/../RayVPN.apk}"

say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$1"; }
[ -f "$ANDROID_JAR" ] || { echo "ANDROID_JAR not found: $ANDROID_JAR"; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/lib"

say "resources"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

say "manifest"
python3 - "$SRC/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import sys
src, dst, code, name = sys.argv[1:5]
xml = open(src, encoding='utf-8').read()
xml = xml.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
    'package="app.rayvpn.client" android:versionCode="%s" android:versionName="%s">' % (code, name))
xml = xml.replace('    <uses-permission',
    '    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="33" />\n    <uses-permission', 1)
open(dst, 'w', encoding='utf-8').write(xml)
PY

"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
    -R "$OUT/res.zip" --java "$OUT/gen" --auto-add-overlay

say "libv2ray"
rm -rf "$OUT/aar" && mkdir -p "$OUT/aar"
unzip -o -q "$ROOT/app/libs/libv2ray.aar" -d "$OUT/aar"
python3 - "$OUT/aar/classes.jar" "$OUT/libv2ray.jar" <<'PY'
import sys, zipfile
src, dst = sys.argv[1:3]
zin, zout = zipfile.ZipFile(src), zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED)
for entry in zin.infolist():
    if entry.filename.endswith('/'):
        continue
    data = zin.read(entry.filename)
    if entry.filename.endswith('.class'):
        data = data.replace(b'MethodParameters', b'SourceDebugExten')
    zout.writestr(entry, data)
zout.close()
PY

say "javac"
if [ -n "$JAVAC_JAR" ]; then
    "$JAVA" -cp "$JAVAC_JAR" com.sun.tools.javac.Main -nowarn -encoding UTF-8 \
        -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" -cp "$OUT/libv2ray.jar" \
        -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')
else
    javac -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$ANDROID_JAR" \
        -cp "$OUT/libv2ray.jar" -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')
fi

say "dex"
if [ -n "$D8_JAR" ]; then
    "$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --min-api 24 --lib "$ANDROID_JAR" \
        --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') "$OUT/libv2ray.jar"
else
    d8 --min-api 24 --lib "$ANDROID_JAR" --output "$OUT/dex" \
        $(find "$OUT/classes" -name '*.class') "$OUT/libv2ray.jar"
fi

say "package"
python3 - "$OUT" "$SRC" "$ROOT" "$APK_OUT.unsigned" <<'PY'
import os, sys, zipfile
out, src, root, target = sys.argv[1:5]
sys.path.insert(0, os.path.join(root, 'tools'))
from apkpack import ApkWriter     # alignment-aware zip writer, see tools/apkpack.py

w = ApkWriter(target)
base = zipfile.ZipFile(os.path.join(out, 'base.apk'))
for entry in base.infolist():
    if entry.filename.endswith('/'):
        continue
    w.add(entry.filename, base.read(entry.filename), entry.compress_type)

w.add_file('classes.dex', os.path.join(out, 'dex/classes.dex'))
for folder, _, files in os.walk(os.path.join(src, 'assets')):
    for name in files:
        full = os.path.join(folder, name)
        w.add_file('assets/' + os.path.relpath(full, os.path.join(src, 'assets')), full)
for name in os.listdir(os.path.join(out, 'aar/assets')):
    w.add_file('assets/' + name, os.path.join(out, 'aar/assets', name))
for abi in ('armeabi-v7a', 'arm64-v8a'):
    w.add_file('lib/%s/libgojni.so' % abi,
               os.path.join(out, 'aar/jni', abi, 'libgojni.so'))
size = w.close()
print('packed', size, 'bytes,', len(w.central), 'entries')
PY

say "sign (APK Signature Scheme v2)"
KEY_PEM="$OUT/key.pem"; CERT_PEM="$OUT/cert.pem"
python3 "$ROOT/tools/jks.py" "$KEYSTORE" "$KEY_ALIAS" "$STORE_PASS" "$OUT" "$KEY_PASS"

node "$ROOT/tools/v2sign.mjs" "$APK_OUT.unsigned" "$APK_OUT" "$KEY_PEM" "$CERT_PEM"
rm -f "$APK_OUT.unsigned" "$KEY_PEM" "$CERT_PEM"

say "verify"
python3 "$ROOT/tools/apkverify.py" "$APK_OUT"
python3 "$ROOT/tools/apkcheck.py" "$APK_OUT"
say "done: $APK_OUT"
