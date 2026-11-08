# Development

## Build

Requires JDK 17+ (Android Studio's bundled JBR works; very new system JDKs can be too new for Gradle 8.14)
and the Android SDK.

```sh
./gradlew assembleDebug                      # app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Release builds are split per CPU architecture (`splits.abi` in `app/build.gradle.kts`):

```sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-release.apk
```

For the alpha, release builds are signed with the debug key so they install over debug builds without
losing the runtime and chats. Switching to a dedicated release key will require one reinstall.

## Test

```sh
./gradlew testDebugUnitTest   # git, editor/Markdown, chat persistence/permissions, remote protocol, MOTD and web detection
./gradlew lintDebug
node --test tools/test-web-runner.cjs  # static serving, proxy/HMR, conflicts, failures, process cleanup
# Explicit device smoke check (requires Node.js in the installed app's runtime):
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.termux.test/dev.yurlungr.app.web.WebRunSmokeInstrumentation
adb uninstall com.termux.test
```

The device smoke runner uses temporary app-cache fixtures. It checks static HTTP serving, loopback
health requests, duplicate-run prevention and managed End/Restart/Close without unlocking the device.
It also checks Vite when the optional `cache/web-vite-smoke/site` fixture is present. The 0.0.2 device
validation included Vite 8.3.6 with transformed modules and HMR. To run the Node integration suite inside
Termux, set `YURLUNGR_TEST_NODE`, `YURLUNGR_TEST_BASH` and `YURLUNGR_TEST_FALSE` to the corresponding
`$PREFIX/bin` executables; these avoid platform-specific executable-path assumptions in the test harness.

Use the matching ABI debug APK for your device, or `app-universal-debug.apk` when unsure.

## Open a screen directly

Debug builds accept a `screen` extra, handy for checking layouts without tapping through the app:

```sh
adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es screen devices
# screens: chat, projects, terminal, agents, devices, settings, checks
adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es file <path>                     # Markdown viewer or editor
adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es project <path> --es tab history # a project tab
```

## Icons

Phosphor icons are vendored, not depended on. To add one, list it in `tools/phosphor-gen.py` and
regenerate:

```sh
mkdir /tmp/ph && cd /tmp/ph && npm pack @phosphor-icons/core && tar xzf phosphor-icons-core-*.tgz
python3 <repo>/tools/phosphor-gen.py > <repo>/app/src/main/java/dev/yurlungr/app/ui/PhosphorIcons.kt
```

Never edit `PhosphorIcons.kt` by hand. Icons whose inner chambers render filled in Compose go in the
generator's `EVEN_ODD` set.

## Release

1. Pull remote changes, finish feature commits, update docs/release notes, and bump `versionName` plus
   `versionCode` in `app/build.gradle.kts`. The ACP client reports the installed APK's version.
2. Commit the release version and create a local annotated tag, for example
   `git tag -a 0.0.2 -m "Yurlungr 0.0.2"`.
3. From that clean tagged commit, run `./gradlew testDebugUnitTest lintDebug assembleRelease` and
   `node --test tools/test-web-runner.cjs`. Keep the four ABI APKs and the universal APK, rename them to
   `yurlungr-<version>-<abi>.apk`, and generate `SHA256SUMS.txt`. Check package version, included ABI and signatures.
4. Push the branch and tag without force. Create the GitHub release with `gh release create <version>
   --verify-tag --title "Yurlungr <version>" --notes-file docs/releases/<version>.md`, passing all APKs and
   `SHA256SUMS.txt` as assets. Publish as a full release so the README's latest-release link resolves.

Release artifacts must come from the tagged commit; don't modify source after tagging without creating
and validating the intended release commit again.

## Conventions

- New screens use the shared components and tokens described in [DESIGN.md](DESIGN.md).
- Parsing and layout logic stays in pure functions with unit tests; UI code stays thin.
- Long or interactive runtime work (installs, sign-ins, clones) runs in a terminal session so its output
  and prompts are visible.
