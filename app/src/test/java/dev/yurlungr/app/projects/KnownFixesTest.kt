package dev.yurlungr.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnownFixesTest {
    private fun ids(log: String, pm: String = "npm") = KnownFixes.match(log, pm).map { it.id }
    private fun one(log: String, pm: String = "npm") = KnownFixes.match(log, pm).single()

    @Test fun `esbuild for another platform`() {
        val log = """
            > vite
            error when starting dev server:
            Error:
            You installed esbuild for another platform than the one you're currently using.
            This won't work because esbuild is written with native code and needs to
            install a platform-specific binary executable.

            Specifically the "@esbuild/linux-arm64" package is present but this platform
            needs the "@esbuild/android-arm64" package instead.
        """.trimIndent()
        val fix = one(log)
        assertEquals("esbuild", fix.id)
        assertEquals("rm -rf node_modules && npm install", fix.command)
        assertEquals("rm -rf node_modules && pnpm install", one(log, "pnpm").command)
    }

    @Test fun `esbuild package missing and version mismatch`() {
        assertEquals(listOf("esbuild"), ids("""Error: The package "@esbuild/android-arm64" could not be found, and is needed by esbuild."""))
        assertEquals(listOf("esbuild"), ids("""Error: Expected "0.21.5" but got "0.19.2"\nHost version "0.21.5" does not match binary version "0.19.2""""))
    }

    @Test fun `next swc falls back to wasm`() {
        val log = """
            ⚠ Attempted to load @next/swc-android-arm64, but it was not installed
            ⚠ Attempted to load @next/swc-wasm-nodejs, but it was not installed
            ⨯ Failed to load SWC binary for android/arm64, see more info here: https://nextjs.org/docs/messages/failed-loading-swc
        """.trimIndent()
        val fix = one(log)
        assertEquals("next-swc", fix.id)
        assertTrue(fix.command!!.startsWith("npm install --save-dev @next/swc-wasm-nodejs@"))
        assertTrue(fix.command!!.contains("require('next/package.json').version"))
        assertTrue(one(log, "yarn").command!!.startsWith("yarn add -D "))
        assertTrue(one(log, "bun").command!!.startsWith("bun add -d "))
    }

    @Test fun `rollup missing native package and unsupported platform`() {
        val missing = """
            Error: Cannot find module '@rollup/rollup-android-arm64'
            Require stack:
            - /data/data/com.termux/files/home/projects/app/node_modules/rollup/dist/native.js
                at Module._resolveFilename (node:internal/modules/cjs/loader:1145:15)
            npm has a bug related to optional dependencies (https://github.com/npm/cli/issues/4828).
        """.trimIndent()
        assertEquals("rollup", one(missing).id)
        assertNotNull(one(missing).command)
        val old = "Error: Your current platform \"android\" and architecture \"arm64\" combination is not yet supported by the native Rollup build."
        assertEquals("rollup-old", one(old).id)
        assertNull(one(old).command)
    }

    @Test fun `sharp uses its wasm build with npm, advice otherwise`() {
        val log = """
            Error: Could not load the "sharp" module using the android-arm64 runtime
            Possible solutions:
            - Ensure optional dependencies can be installed:
                npm install --include=optional sharp
        """.trimIndent()
        assertEquals("npm install --cpu=wasm32 sharp", one(log).command)
        assertNull(one(log, "pnpm").command)
        assertTrue(one(log, "pnpm").agentHint.contains("wasm32"))
    }

    @Test fun `lightningcss and glibc binaries are advice for the agent`() {
        val css = "Error: Cannot find module '../lightningcss.android-arm64.node'\nRequire stack:\n- node_modules/lightningcss/node/index.js"
        assertEquals(listOf("lightningcss"), ids(css))
        assertNull(one(css).command)
        val glibc = "Error: dlopen failed: library \"libc.so.6\" not found: needed by /x/node_modules/foo/build/foo.node in namespace (default)"
        assertEquals(listOf("prebuilt"), ids(glibc))
    }

    @Test fun `node-gyp android_ndk_path gets GYP_DEFINES`() {
        val log = """
            gyp info spawn args [ '/data/data/com.termux/files/usr/lib/node_modules/npm/node_modules/node-gyp/gyp/gyp_main.py' ]
            gyp: Undefined variable android_ndk_path in binding.gyp while trying to load binding.gyp
            gyp ERR! configure error
            gyp ERR! stack Error: `gyp` failed with exit code: 1
        """.trimIndent()
        val fix = one(log, "pnpm")
        assertEquals("gyp-ndk", fix.id)
        assertEquals("android_ndk_path=''", fix.env["GYP_DEFINES"])
        assertEquals("GYP_DEFINES=\"android_ndk_path=''\" pnpm install", fix.command)
    }

    @Test fun `bcrypt suggests bcryptjs without a command`() {
        val log = """
            npm ERR! path /home/projects/api/node_modules/bcrypt
            npm ERR! command sh -c node-pre-gyp install --fallback-to-build
            npm ERR! node-pre-gyp info check checked for "/home/projects/api/node_modules/bcrypt/lib/binding/napi-v3/bcrypt_lib.node" (not found)
        """.trimIndent()
        val fixes = KnownFixes.match(log)
        val bcrypt = fixes.first { it.id == "bcrypt" }
        assertNull(bcrypt.command)
        assertTrue(bcrypt.agentHint.contains("bcryptjs"))
    }

    @Test fun `python wheels map to termux packages`() {
        val pip = """
              Building wheel for numpy (pyproject.toml) ... error
              error: subprocess-exited-with-error
              × Building wheel for numpy (pyproject.toml) did not run successfully.
              │ exit code: 1
              ERROR: Failed building wheel for numpy
            Failed to build numpy
            ERROR: Could not build wheels for numpy, which is required to install pyproject.toml-based projects
        """.trimIndent()
        val fix = one(pip)
        assertEquals("python-wheel", fix.id)
        assertTrue(fix.command!!.startsWith("pkg install -y python-numpy && "))
        assertTrue(fix.command!!.contains("include-system-site-packages = true"))
        assertFalse(fix.title.contains("which"))

        val newer = "ERROR: Failed to build installable wheels for some pyproject.toml based projects (Pillow, lxml)"
        assertEquals("pkg install -y python-pillow python-lxml && ${KnownFixes.VENV_SYSTEM_SITE}", one(newer).command)

        val uv = "  × Failed to build `cryptography==43.0.1`\n  ├─▶ The build backend returned an error"
        assertTrue(one(uv).command!!.contains("python-cryptography"))
    }

    @Test fun `unknown python wheel is advice`() {
        val fix = one("ERROR: Failed building wheel for pyreadstat")
        assertEquals("python-build", fix.id)
        assertNull(fix.command)
    }

    @Test fun `failed to build without a python package is not a wheel failure`() {
        assertEquals(emptyList<String>(), ids("Failed to build app: see above"))
    }

    @Test fun `missing compilers install clang`() {
        val rust = """
               Compiling libc v0.2.155
            error: linker `cc` not found
              |
              = note: No such file or directory (os error 2)
        """.trimIndent()
        assertEquals("pkg install -y clang make", one(rust).command)
        val go = "# runtime/cgo\ncgo: C compiler \"gcc\" not found: exec: \"gcc\": executable file not found in \$PATH"
        assertEquals("clang", one(go).env["CC"])
        assertEquals(listOf("compiler"), ids("gyp ERR! stack Error: not found: make"))
        assertEquals(listOf("compiler"), ids("/bin/sh: clang: command not found"))
        assertEquals(listOf("rust"), ids("error: can't find Rust compiler\nCargo, the Rust package manager, is not installed or is not on PATH."))
    }

    @Test fun `watcher limits suggest polling`() {
        val fix = one("Error: ENOSPC: System limit for number of file watchers reached, watch '/home/projects/app/src'")
        assertEquals("1", fix.env["CHOKIDAR_USEPOLLING"])
        assertEquals("true", fix.env["WATCHPACK_POLLING"])
        assertNull(fix.command)
        assertEquals(listOf("watchers"), ids("Error: EMFILE: too many open files, watch\n    at FSWatcher._handle.onchange"))
    }

    @Test fun `address in use names the port`() {
        assertEquals("Port 3000 is already in use", one("Error: listen EADDRINUSE: address already in use :::3000\n    at Server.setupListenHandle").title)
        assertEquals("Port 8000 is already in use", one("ERROR:    [Errno 98] error while attempting to bind on address ('127.0.0.1', 8000): address already in use").title)
        assertEquals("Port 8080 is already in use", one("listen tcp :8080: bind: address already in use").title)
        assertEquals("The port is already in use", one("OSError: [Errno 98] Address already in use").title)
    }

    @Test fun `vite trying another port is not a failure`() {
        assertEquals(emptyList<String>(), ids("Port 5173 is in use, trying another one...\n  ➜  Local:   http://localhost:5174/"))
    }

    @Test fun `colors are ignored and clean logs match nothing`() {
        assertEquals(listOf("compiler"), ids("\u001B[31merror\u001B[0m: linker `cc` not found"))
        assertEquals(emptyList<String>(), ids("added 312 packages in 14s\n\nfound 0 vulnerabilities"))
        assertEquals(emptyList<String>(), ids(""))
    }

    @Test fun `several issues each once, in order`() {
        val log = "gyp: Undefined variable android_ndk_path in binding.gyp\nError: listen EADDRINUSE: address already in use :::5173\n" +
            "gyp: Undefined variable android_ndk_path in binding.gyp"
        assertEquals(listOf("gyp-ndk", "port"), ids(log))
    }

    @Test fun `package manager comes from the node stack`() {
        val dir = File("x")
        assertEquals("pnpm", KnownFixes.packageManager(listOf(DetectedStack("node", "Node.js (pnpm)", dir, "package.json"))))
        assertEquals("npm", KnownFixes.packageManager(listOf(DetectedStack("python", "Python", dir, "requirements.txt"))))
    }
}
