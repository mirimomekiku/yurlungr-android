package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectVenvTest {
    @get:Rule val tmp = TemporaryFolder()

    private val prefixBin get() = File(tmp.root, "usr/bin").apply { mkdirs() }
    private val projects get() = File(tmp.root, "home/projects")

    /** The runtime's interpreter, which a venv's bin/python links to. */
    private val prefixPython get() = File(prefixBin, "python3.12").apply { if (!exists()) writeText("elf") }

    /**
     * A venv as `python -m venv` makes it on the phone: pyvenv.cfg pointing at the runtime's bin, and
     * bin/python a link to [python] (the runtime's interpreter; null: no bin/python at all).
     */
    private fun venv(dir: File, home: File = prefixBin, python: File? = prefixPython): File = File(dir, ".venv").apply {
        File(this, "bin").mkdirs()
        File(this, "pyvenv.cfg").writeText("home = ${home.path}\ninclude-system-site-packages = true\nversion = 3.12.7\n")
        if (python != null) Files.createSymbolicLink(File(this, "bin/python").toPath(), python.toPath())
    }

    private fun path(f: File?) = f?.invariantSeparatorsPath

    @Test fun `found in the folder or above, up to the project`() {
        val app = File(projects, "app").apply { mkdirs() }
        val v = venv(app)
        val deep = File(app, "src/pkg/sub").apply { mkdirs() }
        assertEquals(path(v), path(ProjectVenv.find(app, projects, prefixBin)))
        assertEquals(path(v), path(ProjectVenv.find(deep, projects, prefixBin)))
    }

    @Test fun `the nearest venv wins`() {
        val app = File(projects, "mono").apply { mkdirs() }
        venv(app)
        val backend = File(app, "backend").apply { mkdirs() }
        val inner = venv(backend)
        assertEquals(path(inner), path(ProjectVenv.find(File(backend, "api").apply { mkdirs() }, projects, prefixBin)))
    }

    @Test fun `never above the project folder`() {
        venv(projects) // ~/projects/.venv belongs to no project
        val app = File(projects, "app").apply { mkdirs() }
        assertNull(ProjectVenv.find(app, projects, prefixBin))
        assertNull(ProjectVenv.find(projects, projects, prefixBin))
    }

    @Test fun `outside projects only the folder itself counts`() {
        val other = File(tmp.root, "elsewhere/proj").apply { mkdirs() }
        val v = venv(other)
        assertEquals(path(v), path(ProjectVenv.find(other, projects, prefixBin)))
        assertNull(ProjectVenv.find(File(other, "src").apply { mkdirs() }, projects, prefixBin))
    }

    @Test fun `copied or fake venvs are not activated`() {
        val app = File(projects, "app").apply { mkdirs() }
        venv(app, home = File(tmp.root, "usr/local/bin").apply { mkdirs() }) // made on another machine
        assertNull(ProjectVenv.find(app, projects, prefixBin))
        val bare = File(projects, "bare").apply { mkdirs() }
        File(bare, ".venv/bin").mkdirs() // no pyvenv.cfg
        assertNull(ProjectVenv.find(bare, projects, prefixBin))
    }

    @Test fun `a venv whose python isn't the runtime's is not activated`() {
        // pyvenv.cfg names this phone's bin (anyone can write that), but bin/python is a committed script.
        val app = File(projects, "committed").apply { mkdirs() }
        venv(app, python = null).also { File(it, "bin/python").writeText("#!/bin/sh\nexec something-else") }
        assertNull(ProjectVenv.find(app, projects, prefixBin))
        // A link that stays in the repository doesn't count either.
        val other = File(projects, "linked").apply { mkdirs() }
        venv(other, python = File(other, "tools/python").apply { parentFile!!.mkdirs(); writeText("x") })
        assertNull(ProjectVenv.find(other, projects, prefixBin))
        // No bin/python at all.
        val bare = File(projects, "nopython").apply { mkdirs() }
        venv(bare, python = null)
        assertNull(ProjectVenv.find(bare, projects, prefixBin))
    }

    @Test fun `an untrusted project's venv is not activated, nor one in a folder inside it`() {
        val app = File(projects, "cloned").apply { mkdirs() }
        venv(app)
        val sub = File(app, "backend").apply { mkdirs() }
        venv(sub)
        // What ProjectTrust.isTrusted answers for a project marked untrusted.
        val root = app.canonicalPath
        val trusted = { d: File -> d.canonicalPath != root && !d.canonicalPath.startsWith("$root/") }
        assertNull(ProjectVenv.find(app, projects, prefixBin, trusted))
        assertNull(ProjectVenv.find(sub, projects, prefixBin, trusted))
        // Once trusted, it's activated.
        assertEquals(path(File(sub, ".venv")), path(ProjectVenv.find(sub, projects, prefixBin) { true }))
    }

    @Test fun `activation puts the venv first on PATH and sets VIRTUAL_ENV`() {
        val v = File("/home/projects/app/.venv")
        val env = mapOf("PATH" to "/usr/libexec/bin:/usr/bin:/home/projects/app/.venv/bin", "HOME" to "/home")
        val active = ProjectVenv.activate(env, v)
        assertEquals(v.path, active["VIRTUAL_ENV"])
        val path = active["PATH"]!!.split(':')
        assertEquals(File(v, "bin").path, path.first())
        assertEquals(1, path.count { it == File(v, "bin").path })
        assertEquals("/home", active["HOME"])
    }
}
