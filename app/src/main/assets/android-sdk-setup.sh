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
source "${PREFIX:?Install the Linux environment first}/libexec/yurlungr/setup-common.sh" || exit $?
setup_init "$HOME/android-sdk-setup.log"

SDK="$HOME/android-sdk"
# From toolchain.sh: the app checks for exactly these versions.
PLATFORM="$YURLUNGR_SDK_PLATFORM"
BUILD_TOOLS="$YURLUNGR_SDK_BUILD_TOOLS"
# Pinned to cmdline-tools 19.0: newer releases turn `sdkmanager` into a shim for a native
# x86-64 `android` binary that can't run on ARM. 19.0's sdkmanager is pure Java.
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
# Wrapper version for new projects. Termux's own `gradle` is 9.x, which AGP 8 can't run on.
WRAPPER_GRADLE="8.14"
# Sessions started before the SDK existed don't have these yet.
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"

# JDK, SDK platform and build-tools, plus Gradle's first download.
require_space 1500

step "Packages (JDK, Gradle, native build tools)"
# Includes Termux's gradle, used only to generate the 8.x wrapper for the test build.
apt_prepare && install_pkgs "${YURLUNGR_SDK_PACKAGES[@]}"

step "Android command-line tools"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
# A newer cmdline-tools (with the x86-64 `android` binary) can't work here; replace it.
[ -e "$SDK/cmdline-tools/latest/bin/android" ] && rm -rf "$SDK/cmdline-tools/latest"
if [ -x "$SDKMANAGER" ]; then
  ok "already installed"
else
  mkdir -p "$SDK/cmdline-tools"
  tmp="$PREFIX/tmp/cmdline-tools.zip"
  step "  downloading the command-line tools (~150 MB)…"
  # No progress meter (it redraws with \r, which shows nothing here); give up on a stalled download.
  if run_logged curl -fsSL --retry 3 --connect-timeout 20 --speed-limit 1024 --speed-time 60 -o "$tmp" "$CMDLINE_TOOLS_URL" \
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
  # 9>&-: a stray sdkmanager must not keep holding the setup lock after this run ends.
  yes 2>/dev/null | with_timeout 600 "$SDKMANAGER" --sdk_root="$SDK" --licenses 9>&- >>"$LOG" 2>&1
  license_status=${PIPESTATUS[1]}
  if [ "$license_status" -ne 0 ]; then bad "SDK license acceptance (exit $license_status)"; fi
  # A stalled download would otherwise hang the setup forever (exit 124 = timed out).
  run_logged with_timeout 1800 "$SDKMANAGER" --sdk_root="$SDK" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" \
    && ok "installed" || bad "sdkmanager (exit $?; 124 = timed out)"
else
  bad "sdkmanager missing; command-line tools did not install"
fi

step "Swap x86-64 build tools for native ones"
BT="$SDK/build-tools/$BUILD_TOOLS"
if [ -d "$BT" ]; then
  for tool in aapt aapt2 aidl zipalign; do
    native=$(command -v "$tool" 2>/dev/null) || { bad "native $tool missing"; continue; }
    case "$native" in "$BT"/*) bad "native $tool resolves to the SDK desktop binary"; continue ;; esac
    ln -sf "$native" "$BT/$tool" && ok "$tool → $native" || bad "native $tool link"
  done
else
  bad "build-tools dir missing"
fi

step "Gradle settings (~/.gradle/gradle.properties)"
mkdir -p "$HOME/.gradle" || exit 73
PROPS="$HOME/.gradle/gradle.properties"
touch "$PROPS" || exit 73
set_prop() { # key value (keys are plain names with dots: escape them for the patterns)
  local key=${1//./\\.}
  if grep -q "^$key=" "$PROPS"; then
    sed -i "s|^$key=.*|$1=$2|" "$PROPS"
  else
    printf '%s=%s\n' "$1" "$2" >> "$PROPS"
  fi
}
if set_prop android.aapt2FromMavenOverride "$PREFIX/bin/aapt2" \
  && grep -q "^android\.aapt2FromMavenOverride=$PREFIX/bin/aapt2\$" "$PROPS"; then
  ok "aapt2 override → $PREFIX/bin/aapt2"
else
  bad "Gradle aapt2 setting"
fi
grep -q '^org\.gradle\.jvmargs=' "$PROPS" || set_prop org.gradle.jvmargs "-Xmx2g -Dfile.encoding=UTF-8" || bad "Gradle memory setting"

step "Versions"
hash -r
run_checks "${YURLUNGR_CHECKS_ANDROIDSDK[@]}"
check sdkmanager "\"$SDKMANAGER\" --sdk_root=\"$SDK\" --version"

if [ "${1:-}" = "--test" ]; then
  if [ "${#FAILED[@]}" -ne 0 ]; then setup_finish ""; fi
  # Never overwrite a project the user may have edited after an earlier setup.
  mkdir -p "$HOME/projects" || exit 73
  APP=$(mktemp -d "$HOME/projects/hello-android-XXXXXX") || exit 73
  step "Test build: $APP"
  mkdir -p "$APP/app/src/main/java/dev/yurlungr/hello" || exit 73
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
    # Never fall back to the project folder: Gradle 9 there is exactly what this avoids.
    tmp=$(mktemp -d "$PREFIX/tmp/gradle-wrapper.XXXXXX") || { bad "temporary folder for the Gradle wrapper"; setup_finish ""; }
    touch "$tmp/settings.gradle"
    (cd "$tmp" && run_logged with_timeout 900 gradle --no-daemon wrapper --gradle-version "$WRAPPER_GRADLE" --distribution-type bin) \
      && cp -r "$tmp/gradlew" "$tmp/gradle" "$APP/" && ok "Gradle $WRAPPER_GRADLE wrapper" || bad "gradle wrapper"
    rm -rf "$tmp"
  fi
  # The first build downloads Gradle and AGP: generous, but not unbounded.
  if run_logged with_timeout 3600 ./gradlew --no-daemon assembleDebug; then
    ok "built app/build/outputs/apk/debug/app-debug.apk"
    yurlungr-install app/build/outputs/apk/debug/app-debug.apk || bad "install prompt for the test app"
  else
    bad "test build (see $LOG)"
  fi
fi

setup_finish "Android SDK ready at $SDK. Build with ./gradlew assembleDebug, install with yurlungr-install <apk>."
