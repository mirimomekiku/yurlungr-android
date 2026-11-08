# Development

## Build

Requires JDK 17+ (Android Studio's bundled JBR works; very new system JDKs can be too new for Gradle 8.14)
and the Android SDK.

```sh
./gradlew assembleDebug                      # app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
./gradlew testDebugUnitTest   # git/gh parsers, graph layout, lexer and editor helpers, chat store, ACP extras, remote protocol
./gradlew lintDebug
```

## Open a screen directly

Debug builds accept a `screen` extra, handy for checking layouts without tapping through the app:

```sh
adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es screen devices
# screens: chat, projects, terminal, agents, devices, settings, checks
adb shell am start -n com.termux/dev.yurlungr.app.MainActivity --es file <path>                     # the editor
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

1. Bump `versionName` (and `versionCode`) in `app/build.gradle.kts`, commit and push.
2. Tag the commit and push the tag: `git tag -a <version> -m "Yurlungr <version>" && git push origin <version>`.
3. `./gradlew assembleRelease`, then rename the APKs to `yurlungr-<version>-<abi>.apk` and write
   `SHA256SUMS.txt` (`sha256sum yurlungr-*.apk > SHA256SUMS.txt`).
4. `gh release create <version> --verify-tag --title "Yurlungr <version>" --notes-file notes.md yurlungr-*.apk SHA256SUMS.txt`.
   Publish as a full release (not a pre-release) so the README's "latest release" link resolves.

## Conventions

- New screens use the shared components and tokens described in [DESIGN.md](DESIGN.md).
- Parsing and layout logic stays in pure functions with unit tests; UI code stays thin.
- Long or interactive runtime work (installs, sign-ins, clones) runs in a terminal session so its output
  and prompts are visible.
