package dev.yurlungr.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileExportTest {
    @Test fun `files that share a name get numbered so none overwrites another`() {
        val names = FileExport.uniqueNames(
            listOf(File("/p/a/index.html"), File("/p/b/index.html"), File("/p/c/INDEX.html"), File("/p/data.json"), File("/p/Makefile"), File("/q/Makefile")),
        ).map { it.second }
        assertEquals(listOf("index.html", "index (2).html", "INDEX (3).html", "data.json", "Makefile", "Makefile (2)"), names)
    }

    @Test fun `dotfiles keep their whole name`() {
        val names = FileExport.uniqueNames(listOf(File("/a/.env"), File("/b/.env"))).map { it.second }
        assertEquals(listOf(".env", ".env (2)"), names)
    }
}
