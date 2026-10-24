#!/usr/bin/env bash
# Fallback debug-APK build that needs NO Google hosts (dl.google.com / maven.google.com).
# The normal build is `./gradlew :app:assembleDebug` (Android Gradle Plugin). Use this only where
# Google's repositories are unreachable.
#
# Toolchain:
#   - Android 14 framework classes + resources: org.robolectric:android-all from Maven Central,
#     pinned by SHA-1 (it is a full framework jar, used here only as the compile classpath and as
#     aapt's framework resource table).
#   - aapt (resources, manifest, R.java), zipalign, apksigner: Ubuntu packages.
#   - Kotlin compile: Gradle + kotlin-jvm plugin from the Gradle plugin portal / Maven Central.
#   - Dex + desugaring: D8 8.7.18 (the version AGP 8.7 bundles) from Google's official R8 release
#     bucket on storage.googleapis.com, pinned by SHA-256. (An earlier version of this script used
#     Ubuntu's old dx with Retrolambda; that combination produced an APK that crashed on launch.)
#
# Output: $OUT/netpilot-debug.apk, signed with a throwaway debug key (NOT for distribution).
#   With NETPILOT_KEYSTORE, NETPILOT_KEY_ALIAS and NETPILOT_KEYSTORE_PASS set: $OUT/netpilot-release.apk,
#   signed with your own key (keep that key: Android only installs updates signed with the same key).
# Usage:  tools/build-apk-no-agp.sh [work-dir]
#   Needs: apt packages aapt zipalign apksigner, plus JDK 17+ and gradle 8.x on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$ROOT/build/no-agp}"
OUT="$WORK/out"
FW_VER="14-robolectric-10818077"
FW_SHA1="94b1490a891e9be559aa35c87cd8a0c163f32d83"
FW_URL="https://repo1.maven.org/maven2/org/robolectric/android-all/$FW_VER/android-all-$FW_VER.jar"
D8_SHA256="58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903"
D8_URL="https://storage.googleapis.com/r8-releases/raw/8.7.18/r8lib.jar"

fetch_pinned() { # url file sum-tool sum
  if [[ ! -f "$2" ]] || ! echo "$4  $2" | "$3" -c --quiet 2>/dev/null; then
    for i in 1 2 3 4; do curl -sSfL -o "$2" "$1" && break; sleep $((i * 5)); done
  fi
  echo "$4  $2" | "$3" -c --quiet
}

mkdir -p "$WORK" "$OUT"
cd "$WORK"

# 1. Framework jar (pinned) and a small framework-res zip for aapt.
fetch_pinned "$FW_URL" android-all.jar sha1sum "$FW_SHA1"
fetch_pinned "$D8_URL" r8lib.jar sha256sum "$D8_SHA256"
if [[ ! -f framework-res.zip ]]; then
  rm -rf fwx && mkdir fwx && (cd fwx && unzip -q ../android-all.jar resources.arsc AndroidManifest.xml && zip -q -0 ../framework-res.zip resources.arsc AndroidManifest.xml)
fi

# 2. Manifest: inject what AGP normally adds from app/build.gradle.kts (keep these in sync).
python3 - "$ROOT/app/src/main/AndroidManifest.xml" AndroidManifest.xml <<'EOF'
import sys
s = open(sys.argv[1]).read()
tag = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
assert tag in s, "manifest root changed; update build-apk-no-agp.sh"
s = s.replace(tag, tag[:-1] + ' package="dev.netpilot.app" android:versionCode="8" android:versionName="0.3.1">\n'
              '    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />', 1)
open(sys.argv[2], "w").write(s)
EOF

# 3. Resources -> res.apk + R.java
rm -rf gen && mkdir gen
aapt package -f -m -J gen -M AndroidManifest.xml -S "$ROOT/app/src/main/res" -I framework-res.zip -F "$OUT/unsigned.apk"

# 4. Kotlin/Java compile against the framework jar (JVM 1.8 bytecode; D8 desugars the rest).
mkdir -p kt
cat > kt/settings.gradle.kts <<'EOF'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "netpilot-no-agp"
EOF
cat > kt/build.gradle.kts <<EOF
plugins { id("org.jetbrains.kotlin.jvm") version "2.0.21" }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
sourceSets["main"].java.srcDirs("$ROOT/core/src/main/kotlin", "$ROOT/app/src/main/kotlin", "$WORK/gen")
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xno-param-assertions", "-Xno-call-assertions")
    }
}
dependencies {
    compileOnly(files("$WORK/android-all.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
tasks.register<Copy>("runtimeJars") { from(configurations.runtimeClasspath); into("$WORK/libs") }
EOF
rm -rf libs
(cd kt && gradle --no-daemon -q classes runtimeJars)

# 5. Merge classes (drop multi-release / module-info entries), keep META-INF/services, dex with D8.
rm -rf merged && mkdir -p merged
cp -r kt/build/classes/kotlin/main/. merged/
[[ -d kt/build/classes/java/main ]] && cp -r kt/build/classes/java/main/. merged/
for j in libs/*.jar; do (cd merged && unzip -q -o "../$j" -x 'META-INF/versions/*' 'module-info.class' 'META-INF/*.kotlin_module' 'META-INF/*.MF' 'META-INF/*.SF' 'META-INF/*.RSA'); done
rm -f program.jar && (cd merged && zip -q -r ../program.jar . -i '*.class')
rm -rf dex && mkdir dex
java -cp r8lib.jar com.android.tools.r8.D8 --debug --min-api 29 --lib android-all.jar --output dex program.jar
[[ -f dex/classes.dex && ! -f dex/classes2.dex ]] || { echo "error: expected exactly one classes.dex" >&2; exit 1; }
cp dex/classes.dex classes.dex

# 6. Package dex + service-loader files, align, sign.
cp classes.dex "$OUT/classes.dex"
(cd "$OUT" && zip -q unsigned.apk classes.dex && rm classes.dex)
if [[ -d merged/META-INF/services ]]; then (cd merged && zip -q -r "$OUT/unsigned.apk" META-INF/services); fi
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ -n "${NETPILOT_KEYSTORE:-}" ]]; then
  # Your own release key. Passwords are read from the environment by apksigner itself, so they
  # never appear on a command line (visible to other processes) or in this script.
  : "${NETPILOT_KEY_ALIAS:?set NETPILOT_KEY_ALIAS}" "${NETPILOT_KEYSTORE_PASS:?set NETPILOT_KEYSTORE_PASS}"
  NAME=netpilot-release.apk
  apksigner sign --ks "$NETPILOT_KEYSTORE" --ks-key-alias "$NETPILOT_KEY_ALIAS" --ks-pass env:NETPILOT_KEYSTORE_PASS \
    --key-pass env:"${NETPILOT_KEY_PASS_VAR:-NETPILOT_KEYSTORE_PASS}" --min-sdk-version 29 --out "$OUT/$NAME" "$OUT/aligned.apk"
else
  NAME=netpilot-debug.apk
  KS="$WORK/debug.keystore"
  [[ -f "$KS" ]] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NetPilot Debug" >/dev/null 2>&1
  apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --min-sdk-version 29 \
    --out "$OUT/$NAME" "$OUT/aligned.apk"
fi
apksigner verify --min-sdk-version 29 "$OUT/$NAME"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/$NAME.idsig"
sha256sum "$OUT/$NAME"
