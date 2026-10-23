#!/usr/bin/env bash
# Fallback debug-APK build that needs NO Google hosts (dl.google.com / maven.google.com).
# The normal build is `./gradlew :app:assembleDebug` (Android Gradle Plugin). Use this only where
# Google's repositories are unreachable.
#
# Toolchain:
#   - Android 14 framework classes + resources: org.robolectric:android-all from Maven Central,
#     pinned by SHA-1 (it is a full framework jar, used here only as the compile classpath and as
#     aapt's framework resource table).
#   - aapt (resources, manifest, R.java), dalvik-exchange/dx (dex), zipalign, apksigner: Ubuntu packages.
#   - Kotlin compile: Gradle + kotlin-jvm plugin from the Gradle plugin portal / Maven Central.
#   - Lambda desugaring: Retrolambda from Maven Central (pinned), run on a Java 8 JDK. The Kotlin
#     stdlib and kotlinx-coroutines use invokedynamic lambdas, which crash on ART without desugaring;
#     AGP's D8 normally does this. Its classpath is Ubuntu's API 23 android.jar because the API 34
#     framework jar is Java 17 bytecode that a Java 8 VM cannot load.
#
# Output: $OUT/netpilot-debug.apk, signed with a throwaway debug key (NOT for distribution).
# Usage:  tools/build-apk-no-agp.sh [work-dir]
#   Needs: apt packages aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
#          openjdk-8-jdk-headless, plus JDK 17+ and gradle 8.x on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${1:-$ROOT/build/no-agp}"
OUT="$WORK/out"
FW_VER="14-robolectric-10818077"
FW_SHA1="94b1490a891e9be559aa35c87cd8a0c163f32d83"
FW_URL="https://repo1.maven.org/maven2/org/robolectric/android-all/$FW_VER/android-all-$FW_VER.jar"
RL_SHA1="f859c73cd89a3a5038372f4984a0422f6b7b0eed"
RL_URL="https://repo1.maven.org/maven2/net/orfjackal/retrolambda/retrolambda/2.5.7/retrolambda-2.5.7.jar"
JAVA8="${JAVA8:-/usr/lib/jvm/java-8-openjdk-amd64/bin/java}"
API23_JAR="${API23_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
DX="$(command -v dx || command -v dalvik-exchange)"

fetch_pinned() { # url file sha1
  if [[ ! -f "$2" ]] || ! echo "$3  $2" | sha1sum -c --quiet 2>/dev/null; then
    for i in 1 2 3 4; do curl -sSfL -o "$2" "$1" && break; sleep $((i * 5)); done
  fi
  echo "$3  $2" | sha1sum -c --quiet
}

mkdir -p "$WORK" "$OUT"
cd "$WORK"

# 1. Framework jar (pinned) and a small framework-res zip for aapt.
fetch_pinned "$FW_URL" android-all.jar "$FW_SHA1"
fetch_pinned "$RL_URL" retrolambda.jar "$RL_SHA1"
if [[ ! -f framework-res.zip ]]; then
  rm -rf fwx && mkdir fwx && (cd fwx && unzip -q ../android-all.jar resources.arsc AndroidManifest.xml && zip -q -0 ../framework-res.zip resources.arsc AndroidManifest.xml)
fi

# 2. Manifest: inject what AGP normally adds from app/build.gradle.kts (keep these in sync).
python3 - "$ROOT/app/src/main/AndroidManifest.xml" AndroidManifest.xml <<'EOF'
import sys
s = open(sys.argv[1]).read()
tag = '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
assert tag in s, "manifest root changed; update build-apk-no-agp.sh"
s = s.replace(tag, tag[:-1] + ' package="dev.netpilot.app" android:versionCode="2" android:versionName="0.2.0">\n'
              '    <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />', 1)
open(sys.argv[2], "w").write(s)
EOF

# 3. Resources -> res.apk + R.java
rm -rf gen && mkdir gen
aapt package -f -m -J gen -M AndroidManifest.xml -S "$ROOT/app/src/main/res" -I framework-res.zip -F "$OUT/unsigned.apk"

# 4. Kotlin/Java compile against the framework jar (JVM 1.8 bytecode, no invokedynamic for dx).
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

# 5. Merge classes (drop multi-release / module-info entries dx cannot read), keep META-INF/services.
rm -rf merged && mkdir -p merged
cp -r kt/build/classes/kotlin/main/. merged/
[[ -d kt/build/classes/java/main ]] && cp -r kt/build/classes/java/main/. merged/
for j in libs/*.jar; do (cd merged && unzip -q -o "../$j" -x 'META-INF/versions/*' 'module-info.class' 'META-INF/*.kotlin_module' 'META-INF/*.MF' 'META-INF/*.SF' 'META-INF/*.RSA'); done
rm -rf retro && mkdir retro
"$JAVA8" -Dretrolambda.inputDir=merged -Dretrolambda.outputDir=retro \
  -Dretrolambda.classpath="merged:$API23_JAR" -Dretrolambda.bytecodeVersion=51 -Dretrolambda.defaultMethods=true \
  -javaagent:retrolambda.jar -jar retrolambda.jar > retrolambda.log 2>&1 || { tail -30 retrolambda.log >&2; exit 1; }
if grep -rlq --include='*.class' 'java/lang/invoke/LambdaMetafactory' retro; then
  echo "error: invokedynamic lambdas remain after desugaring; dx output would crash on ART" >&2; exit 1
fi
"$DX" --dex --min-sdk-version=26 --output="$WORK/classes.dex" retro

# 6. Package dex + service-loader files, align, sign.
cp classes.dex "$OUT/classes.dex"
(cd "$OUT" && zip -q unsigned.apk classes.dex && rm classes.dex)
if [[ -d merged/META-INF/services ]]; then (cd merged && zip -q -r "$OUT/unsigned.apk" META-INF/services); fi
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="$WORK/debug.keystore"
[[ -f "$KS" ]] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=NetPilot Debug" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --min-sdk-version 29 \
  --out "$OUT/netpilot-debug.apk" "$OUT/aligned.apk"
apksigner verify --min-sdk-version 29 "$OUT/netpilot-debug.apk"
rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/netpilot-debug.apk.idsig"
sha256sum "$OUT/netpilot-debug.apk"
