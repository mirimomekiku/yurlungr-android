package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectVenvTest {
    @get:Rule val tmp = TemporaryFolder()

    private val prefixBin get() = File(tmp.root, "usr/bin").apply { mkdirs() }
    private val projects get() = File(tmp.root, "home/projects")

    /** A venv as `python -m venv` makes it on the phone: pyvenv.cfg pointing at the runtime's python. */
    private fun venv(dir: File, home: File = prefixBin): File = File(dir, ".venv").apply {
        File(this, "bin").mkdirs()
        File(this, "pyvenv.cfg").writeText("home = ${home.path}\ninclude-system-site-packages = true\nversion = 3.12.7\n")
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
