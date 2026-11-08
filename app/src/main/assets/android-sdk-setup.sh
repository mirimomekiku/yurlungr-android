#!/usr/bin/env bash
# Sets up an Android SDK that Gradle on this phone can build with.
# Google's build-tools are x86-64 Linux binaries, so the ones Gradle actually executes
# (aapt2, aapt, aidl) are swapped for Termux's native builds, and AGP is pointed at
# Termux's aapt2. Platform jars and everything Java-based work as shipped.
#
#   bash android-sdk-setup.sh          set up / repair
#   bash android-sdk-setup.sh --test   also build and install a tiny test app
# Safe to re-run.
set -u

SDK="$HOME/android-sdk"
PLATFORM="android-36"
BUILD_TOOLS="36.0.0"
# Pinned to cmdline-tools 19.0: newer releases turn `sdkmanager` into a shim for a native
# x86-64 `android` binary that can't run on ARM. 19.0's sdkmanager is pure Java.
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
# Wrapper version for new projects. Termux's own `gradle` is 9.x, which AGP 8 can't run on.
WRAPPER_GRADLE="8.14"
LOG="$HOME/android-sdk-setup.log"
# Sessions started before the SDK existed don't have these yet.
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confnew)
export DEBIAN_FRONTEND=noninteractive
FAILED=()

COLS=$(stty size 2>/dev/null | cut -d' ' -f2)
COLS=${COLS:-80}
step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m  ✓ %s\033[0m\n' "$*"; }
bad()  { printf '\033[31m  ✗ %s\033[0m\n' "$*"; FAILED+=("$1"); }
run_logged() {
  "$@" 2>&1 | tee -a "$LOG" | while IFS= read -r line; do
    line=${line##*$'\r'}
    [ -n "$line" ] && printf '\r\033[K\033[2m  %s\033[0m' "${line:0:$((COLS - 3))}"
  done
  local status=${PIPESTATUS[0]}
  printf '\r\033[K'
  return "$status"
}

: > "$LOG"

step "Packages (JDK, native build tools)"
run_logged apt-get update || true
for p in openjdk-17 aapt aapt2 aidl apksigner d8 unzip curl; do
  run_logged apt-get install "${APT_OPTS[@]}" "$p" && ok "$p" || bad "$p"
done

step "Android command-line tools"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
# A newer cmdline-tools (with the x86-64 `android` binary) can't work here; replace it.
[ -e "$SDK/cmdline-tools/latest/bin/android" ] && rm -rf "$SDK/cmdline-tools/latest"
if [ -x "$SDKMANAGER" ]; then
  ok "already installed"
else
  mkdir -p "$SDK/cmdline-tools"
  tmp="$PREFIX/tmp/cmdline-tools.zip"
  if run_logged curl -fL --retry 3 -o "$tmp" "$CMDLINE_TOOLS_URL" \
    && rm -rf "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest" \
    && run_logged unzip -q -o "$tmp" -d "$SDK/cmdline-tools" \
    && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"; then
    ok "installed"
  else
    bad "cmdline-tools"
  fi
  rm -f "$tmp"
fi

step "SDK platform $PLATFORM and build-tools $BUILD_TOOLS"
if [ -x "$SDKMANAGER" ]; then
  yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$SDK" --licenses >>"$LOG" 2>&1
  run_logged "$SDKMANAGER" --sdk_root="$SDK" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" \
    && ok "installed" || bad "sdkmanager"
fi

step "Swap x86-64 build tools for native ones"
BT="$SDK/build-tools/$BUILD_TOOLS"
if [ -d "$BT" ]; then
  for tool in aapt aapt2 aidl zipalign; do
    native=$(command -v "$tool" 2>/dev/null) || continue
    case "$native" in "$BT"/*) continue ;; esac
    ln -sf "$native" "$BT/$tool" && ok "$tool → $native"
  done
else
  bad "build-tools dir missing"
fi

step "Gradle settings (~/.gradle/gradle.properties)"
mkdir -p "$HOME/.gradle"
PROPS="$HOME/.gradle/gradle.properties"
touch "$PROPS"
set_prop() { # key value
  if grep -q "^$1=" "$PROPS"; then
    sed -i "s|^$1=.*|$1=$2|" "$PROPS"
  else
    printf '%s=%s\n' "$1" "$2" >> "$PROPS"
  fi
}
set_prop android.aapt2FromMavenOverride "$PREFIX/bin/aapt2"
grep -q '^org.gradle.jvmargs=' "$PROPS" || set_prop org.gradle.jvmargs "-Xmx2g -Dfile.encoding=UTF-8"
ok "aapt2 override → $PREFIX/bin/aapt2"

if [ "${1:-}" = "--test" ]; then
  step "Test build: ~/projects/hello-android"
  APP="$HOME/projects/hello-android"
  mkdir -p "$APP/app/src/main/java/dev/yurlungr/hello"
  cat > "$APP/settings.gradle" <<'EOF'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "hello-android"
include ":app"
EOF
  printf '%s\n' .gradle/ .kotlin/ build/ local.properties > "$APP/.gitignore"
  cat > "$APP/build.gradle" <<'EOF'
plugins { id "com.android.application" version "8.13.1" apply false }
EOF
  cat > "$APP/app/build.gradle" <<'EOF'
plugins { id "com.android.application" }
android {
    namespace "dev.yurlungr.hello"
    compileSdk 36
    defaultConfig {
        applicationId "dev.yurlungr.hello"
        minSdk 24
        targetSdk 36
        versionCode 1
        versionName "1.0"
    }
}
EOF
  cat > "$APP/app/src/main/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Hello from Yurlungr">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF
  cat > "$APP/app/src/main/java/dev/yurlungr/hello/MainActivity.java" <<'EOF'
package dev.yurlungr.hello;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Built on this phone.");
        text.setTextSize(24);
        text.setPadding(48, 96, 48, 48);
        setContentView(text);
    }
}
EOF
  cd "$APP" || exit 1
  if [ ! -x gradlew ]; then
    # Generate the wrapper in an empty build: running Termux's Gradle 9 inside the project
    # would apply AGP 8, which fails there.
    tmp=$(mktemp -d)
    touch "$tmp/settings.gradle"
    (cd "$tmp" && run_logged gradle wrapper --gradle-version "$WRAPPER_GRADLE" --distribution-type bin) \
      && cp -r "$tmp/gradlew" "$tmp/gradle" "$APP/" && ok "Gradle $WRAPPER_GRADLE wrapper" || bad "gradle wrapper"
    rm -rf "$tmp"
  fi
  if run_logged ./gradlew --no-daemon assembleDebug; then
    ok "built app/build/outputs/apk/debug/app-debug.apk"
    yurlungr-install app/build/outputs/apk/debug/app-debug.apk
  else
    bad "test build (see $LOG)"
  fi
fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  printf '\033[1;32mAndroid SDK ready\033[0m at %s. Build with ./gradlew assembleDebug, install with yurlungr-install <apk>.\n' "$SDK"
else
  printf '\033[1;31mFailed: %s\033[0m (details: %s)\n' "${FAILED[*]}" "$LOG"
fi
