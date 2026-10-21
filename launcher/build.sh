#!/usr/bin/env bash
# Builds TV Home without Gradle: aapt2 + javac + d8 + apksigner.
# Needs ANDROID_HOME with a platforms;android-NN (34 or newer) and build-tools;35.0.0.
# Works on Linux, macOS and Windows (Git Bash: the SDK ships .exe/.bat tools there).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-34/android.jar"
if [ ! -f "$JAR" ]; then
    JAR="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
fi
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"

# On Windows some tools are .bat wrappers that bash cannot exec directly.
tool() {
    local name="$1"; shift
    if [ -f "$BT/$name.bat" ]; then
        cmd //c "$(cygpath -w "$BT/$name.bat")" "$@"
    else
        "$BT/$name" "$@"
    fi
}
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

"$BT/aapt2" compile --dir res -o "$OUT/res/"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    -A assets --java "$OUT/gen" "$OUT"/res/*.flat
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -classpath "$JAR" \
    -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java')
# One jar for d8: listing every class file overflows the Windows command line.
(cd "$OUT/classes" && jar cf ../classes.jar .)
tool d8 --release --min-api 23 --lib "$(winpath "$JAR")" --output "$(winpath "$OUT/dex")" \
    "$(winpath "$OUT/classes.jar")"
cp "$OUT/unsigned.apk" "$OUT/with-dex.apk"
if command -v zip >/dev/null 2>&1; then
    (cd "$OUT/dex" && zip -qj ../with-dex.apk classes.dex)
else
    python -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" \
        "$OUT/with-dex.apk" "$OUT/dex/classes.dex"
fi
"$BT/zipalign" -f -p 4 "$OUT/with-dex.apk" "$OUT/aligned.apk"

KEY="${TVHOME_KEYSTORE:-$HOME/.android/tvhome.keystore}"
if [ ! -f "$KEY" ]; then
    mkdir -p "$(dirname "$KEY")"
    keytool -genkeypair -keystore "$KEY" -storepass tvhome -keypass tvhome -alias tvhome \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Home" >/dev/null
fi
tool apksigner sign --ks "$(winpath "$KEY")" --ks-pass pass:tvhome --key-pass pass:tvhome \
    --v4-signing-enabled false --out "$(winpath ../dist/tv-home.apk)" "$(winpath "$OUT/aligned.apk")"
tool apksigner verify "$(winpath ../dist/tv-home.apk)"
echo "Built ../dist/tv-home.apk"
