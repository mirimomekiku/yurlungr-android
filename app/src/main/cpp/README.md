# Termux terminal JNI

`termux.c` comes from `termux/termux-app` tag `v0.118.3`:
https://github.com/termux/termux-app/blob/v0.118.3/terminal-emulator/src/main/jni/termux.c

It matches the Java terminal-emulator dependency. The terminal libraries are
covered by the Apache 2.0 exception in upstream's LICENSE.md; the license is
bundled in `../assets/licenses/Termux-Apache-2.0.txt`.

The app rebuilds this library using NDK r28 with explicit 16 KB maximum and common
page sizes. The Gradle `prepareTermuxEmulator` task removes prebuilt JNI libraries
from the upstream AAR while retaining its Java classes and other resources.

Local source change: release `cmd_cwd` against its original `cwd` JNI string,
rather than `cmd`. Keep the source and dependency version in sync when updating.
