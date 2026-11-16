# Development

## Build

Requires JDK 17+ (Android Studio's bundled JBR works; very new system JDKs can be too new for Gradle 8.14)
and the Android SDK.

The terminal JNI library is compiled locally with Android NDK `28.2.13676358` and
CMake `3.22.1` (install these through Android Studio's SDK Manager). Its source is
in `app/src/main/cpp`; keep it matched to the Termux version in the version catalog.
The build retains the upstream emulator's Java classes and replaces its prebuilt
native libraries, so changing only the NDK setting is not enough.

```sh
./gradlew assembleDebug                      # app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Release builds are split per CPU architecture (`splits.abi` in `app/build.gradle.kts`):

```sh
./gradlew assembleRelease
# app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,x86_64,x86,universal}-release.apk
```

Release builds are signed with the release key when one is configured (see [Release signing](#release-signing)).
Without one they fall back to this machine's debug key, with a Gradle warning, so they install over debug
builds without losing the runtime and chats (the alpha workflow).

Minification (`isMinifyEnabled`) is off. Before a wide release, turn it on with R8 keep rules for what
reflection, JNI (`libtermux`) and kotlinx.serialization need, and test a release build on a device.

## Test

```sh
./gradlew testDebugUnitTest   # git, editor/Markdown, chat persistence/permissions/artifacts, remote protocol, MOTD, web detection and tunnels
./gradlew lintDebug
node --test tools/test-web-*.cjs  # launch adapters, readiness, SSR/HMR, failures, process cleanup
# Explicit device smoke check (requires Node.js in the installed app's runtime):
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.termux.test/dev.yurlungr.app.web.WebRunSmokeInstrumentation
adb uninstall com.termux.test
```

The device smoke runner uses temporary app-cache fixtures. It checks static HTTP serving, loopback
health requests, duplicate-run prevention and managed End/Restart/Close without unlocking the device.
It also checks framework launches and Close with optional fixtures at `cache/web-vite-smoke/site`,
`cache/web-next-smoke/site`, and `cache/web-astro-smoke/site` (each must have its dependencies installed
and a page containing `Yurlungr <Vite|Next|Astro> device check`; Vite needs `/main.js`, Next `/api/check`).

On arm64 with Termux Node 24.22.2, local runtime checks cover Vite 8.3.6 module transformation/HMR,
Next.js 16.4.5 Webpack with its matching WASM fallback (Pages/App Router HTTP, SSR public-origin
headers, HMR and compiler recovery), and Astro 7.4.7 HTTP serving. An isolated copy of a Next 15.3.5/Tailwind 4.1.11/DaisyUI 5.0.43 repository
also checks the missing Lightning CSS Android binding fallback with exact-version WASM and generated CSS.
The optional `cache/web-project-regression/site` fixture is checked by Android instrumentation when
present; omit credentials and `.env` files from copied fixtures. These checks do not guarantee every repo works.
Nuxt, Angular, CRA, Parcel and Webpack launch adapters have automated recipe tests; their native
packages and project setup still need device testing. Detection does not block Run pending validation.

To run the Node integration suite inside Termux, set `YURLUNGR_TEST_NODE`, `YURLUNGR_TEST_BASH` and `YURLUNGR_TEST_FALSE` to the corresponding
`$PREFIX/bin` executables; these avoid platform-specific executable-path assumptions in the test harness.

Use the matching ABI debug APK for your device, or `app-universal-debug.apk` when unsure.

### Native library alignment and smoke check

Check ELF LOAD/RELRO alignment and uncompressed ZIP offsets for every bundled
64-bit library using Python (no extra packages):

```sh
python tools/check-native-alignment.py app/build/outputs/apk/debug/app-arm64-v8a-debug.apk app/build/outputs/apk/debug/app-x86_64-debug.apk app/build/outputs/apk/debug/app-universal-debug.apk
```

The existing instrumentation runner also offers a bootstrap-independent native
check. After installing the app and test APKs built above, run:

```sh
adb shell am instrument -w -e nativeOnly true com.termux.test/dev.yurlungr.app.web.WebRunSmokeInstrumentation
```

This creates and resizes a PTY, executes `/system/bin/sh`, checks the output and
exit status, then closes it. It runs before the full web smoke suite as well.
For actual 16 KB runtime validation, `adb shell getconf PAGE_SIZE` must report
`16384`; on that device also test bootstrap installation, terminals and agents.
The checker covers packaged native libraries, not downloaded runtime binaries.

### Setup scripts

Shell assets use LF in Git. `RuntimeAssets` also removes a leading UTF-8 BOM and
normalizes CRLF/lone CR when installing them, including scripts from older APKs.
Executable files are published by atomic rename so a refresh cannot leave a
partially written script. Setup launchers parse with `bash -n` before executing.

The developer-tools, agent and SDK installers share an exclusive kernel lock, preserve one previous
log (`*.log.previous`), and return nonzero on a failed step. The terminal shows
the exit code before returning to its shell. Retry setup after fixing the reported
failure; it will update or repair the existing installation.

**On-device checks** (no screen interaction; temporary folders only). With the debug and test APKs installed:

```sh
# The runtime features: environment, JDK, git stage/unstage/discard, search, processes, ports, disk, updates
adb shell am instrument -w -e features true com.termux.test/dev.yurlungr.app.web.WebRunSmokeInstrumentation
# The in-runtime self-test (quick), or "full" (installs two small packs, switches the JDK and back, runs Vite)
adb shell am instrument -w -e selfTest quick com.termux.test/dev.yurlungr.app.web.WebRunSmokeInstrumentation
```

`am instrument` restarts the app's process, so open chats and terminals end.

Package lists, SDK versions and version checks live in one place, `runtime/Toolchain.kt`.
On every launch the app writes them to `$PREFIX/libexec/yurlungr/toolchain.sh`. The setup
scripts source that file, and System checks probes the same tools, so neither can drift
from the other. To add a tool to an existing setup, add it to `Toolchain` and nothing else.

- **A new optional pack** needs:
  - a `ToolSetup` entry (packages, disk space, marker file, one-line blurb) and its `Tool` probes;
  - a `Fix` entry, plus its branches in `SetupScreen`.

  No script to write: the generic `assets/pack-setup.sh` is installed under the pack's script name and
  installs that pack (a pack needing custom steps gets its own asset, like `browser-setup.sh`, and an
  exception in `ToolSetup.asset`). `HardeningTest` checks that every pack has all of these.
- **Probes:**
  - print a version line and exit 0;
  - avoid `head` in a pipe: `pipefail` turns its early exit (SIGPIPE) into a failure, so use `sed -n 1p`;
  - tools that start a JVM need `timeoutS = SLOW_PROBE_S`;
  - tools that may not be installed (optional agents) need `onlyIf`, so neither System checks nor the
    scripts report them as failures.
- **JDK versions** live in `runtime/Jdks.kt`. Termux's Gradle, Kotlin, Maven, d8 and apksigner need
  JDK 21, so that's the one the setups install.

Each setup writes `$PREFIX/var/lib/yurlungr/setup/<name>.status` when it exits: the exit code,
log path, time and failed steps. System checks shows a failed run with a **Log** button.
Setups wait up to two minutes for an apt lock held by a terminal, repair an interrupted dpkg
run first, and stop with a mirror hint when the package index can't be downloaded.

A setup records `exit -1` with its pid as soon as it holds the lock, so a run killed outright reads
as interrupted, never as passed. It records 129 when its terminal is closed, 130 for Ctrl-C and
143 when stopped. Steps run with stdin from `/dev/null`, so a prompt fails at once instead of
waiting unseen. Long steps run under `with_timeout`: `timeout --foreground -k`, so Ctrl-C still
reaches them. Anything started in the background must close the lock fd (`9>&-`), as
`run_logged` and `check` do, or it keeps holding the setup lock after its setup is gone.

Processes are always stopped as a tree (`runtime/ProcessTree.kt`): descendants are collected
from `/proc` *before* anything is killed, then sent SIGHUP and SIGTERM, then SIGKILL. This applies
to terminal End/Close/Restart, `CommandRunner` timeouts and cancelled commands. Never call
`TerminalSession.finishIfRunning()`: for a session whose process never started, its pid is 0, and
`kill(0, SIGKILL)` kills the app's own process group. The app process ignores SIGHUP, and ignored
signals survive exec, so `termux.c` resets every signal to its default in the terminal child.
Processes started by `CommandRunner` still inherit SIGHUP ignored; the tree stop relies on
SIGTERM/SIGKILL for them. The terminal child exits 127 when the program is missing and 126 when
it can't run or its working folder is gone, instead of continuing in `/`.

A base-system install marks itself incomplete for its whole duration, which blocks new terminals,
chats and setups. It extracts into staging and only then swaps the result in, so a failed
download or extract leaves the old system untouched. It runs in an app-wide job, so leaving the
screen doesn't cancel it.

`CommandRunner` never throws: a program that's missing or can't run comes back as exit
127 or 126 with `launchError` set, and `CommandResult.describe()` turns any failure into one
message for the user. Code that parses git output gets `GitStatus.error` or a failed `Result`,
never an empty list or "not a repository".

After installing the app and instrumentation APKs, this device check validates
installed scripts and exercises command/log failures, retries and lock contention
inside a temporary cache fixture, without downloading or installing packages:

```sh
adb shell am instrument -w -e scriptsOnly true com.termux.test/dev.yurlungr.app.web.WebRunSmokeInstrumentation
```

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

## Release signing

The release key is read from these values, each from a Gradle property (`-P…` or
`~/.gradle/gradle.properties`), an environment variable, or `local.properties`, in that order:

| Name | Value |
|---|---|
| `YURLUNGR_KEYSTORE` | Path to the keystore (relative paths are from the repository root) |
| `YURLUNGR_KEYSTORE_PASSWORD` | Keystore password |
| `YURLUNGR_KEY_ALIAS` | Key alias |
| `YURLUNGR_KEY_PASSWORD` | Key password (defaults to the keystore password, as in PKCS12 keystores) |

Create the key once, outside the repository (`*.jks` and `*.keystore` are gitignored anyway), and back it
up together with its passwords: every future update must be signed with it.

```sh
keytool -genkeypair -v -keystore ~/keys/yurlungr-release.jks -alias yurlungr   -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Yurlungr"
```

Then, for example in `local.properties`:

```properties
YURLUNGR_KEYSTORE=/home/me/keys/yurlungr-release.jks
YURLUNGR_KEYSTORE_PASSWORD=…
YURLUNGR_KEY_ALIAS=yurlungr
```

**Switching keys is a one-time, deliberate step.** Android refuses to install an APK over one signed with a
different key, so the first release-key build needs the old app uninstalled, which wipes its data: the
embedded Linux environment, installed packs, agent sign-ins and all chats (projects in shared storage
stay). Do it once, announce it in the release notes, and never change the key again.

For the GitHub release workflow, add the repository secrets `YURLUNGR_KEYSTORE_BASE64`
(`base64 -w0 yurlungr-release.jks`), `YURLUNGR_KEYSTORE_PASSWORD`, `YURLUNGR_KEY_ALIAS` and
`YURLUNGR_KEY_PASSWORD`. It sets `YURLUNGR_REQUIRE_RELEASE_KEY=true`, which makes the build fail rather
than fall back to a debug key.

## Release workflow

`.github/workflows/release.yml` runs when a tag is pushed (`0.0.5`, or `v0.0.5`). It checks that the tag
matches `versionName`, builds `assembleRelease` with the release key from the repository secrets, renames
the APKs to `yurlungr-<version>-<abi>.apk`, writes `SHA256SUMS.txt`, and creates a **draft** GitHub release
with them and `docs/releases/<version>.md` as notes. Review the draft and publish it.

The Gradle wrapper's download is pinned by `distributionSha256Sum` in
`gradle/wrapper/gradle-wrapper.properties`; update it with the wrapper version (the checksum is published
at `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256`).

Dependencies are checked against `gradle/verification-metadata.xml` (SHA-256). After adding or upgrading a
dependency or plugin, regenerate it and review the diff:

```sh
./gradlew --write-verification-metadata sha256 help testDebugUnitTest lintDebug assembleDebug assembleRelease
```

AGP's `aapt2` jar is per OS: the generated file only lists the one for the machine that ran it, so add the
`-linux` (release workflow) and `-osx` entries by hand from `https://dl.google.com/android/maven2/com/android/tools/build/aapt2/`.

## Release

1. Pull remote changes, finish feature commits, update docs/release notes, and bump `versionName` plus
   `versionCode` in `app/build.gradle.kts`. The ACP client reports the installed APK's version.
2. Commit the release version and create a local annotated tag, for example
   `git tag -a 0.0.4 -m "Yurlungr 0.0.4"`.
3. From that clean tagged commit, run `./gradlew testDebugUnitTest lintDebug assembleRelease` and
   `node --test tools/test-web-*.cjs`. Keep the four ABI APKs and the universal APK, rename them to
   `yurlungr-<version>-<abi>.apk`, and generate `SHA256SUMS.txt`. Check package version, included ABI and signatures.
4. Push the branch and tag without force. With the signing secrets set, the release workflow builds the APKs
   and `SHA256SUMS.txt` and opens a draft release: check its APKs (version, ABI, signing certificate) and publish it. Without the workflow, create the release with `gh release create <version>
   --verify-tag --title "Yurlungr <version>" --notes-file docs/releases/<version>.md`, passing all APKs and
   `SHA256SUMS.txt` as assets. Publish as a full release so the README's latest-release link resolves.

Release artifacts must come from the tagged commit; don't modify source after tagging without creating
and validating the intended release commit again.

## Conventions

- New screens use the shared components and tokens described in [DESIGN.md](DESIGN.md).
- Parsing and layout logic stays in pure functions with unit tests; UI code stays thin.
- Long or interactive runtime work (installs, sign-ins, clones) runs in a terminal session so its output
  and prompts are visible.
