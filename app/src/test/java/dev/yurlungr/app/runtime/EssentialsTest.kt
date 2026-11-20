package dev.yurlungr.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EssentialsTest {
    private fun path(script: String) = "\"\$PREFIX/libexec/yurlungr/$script\""
    private val essentials = path("essentials-setup.sh")
    private val agents = path("agents-setup.sh")

    @Test fun `every essential is probed by System checks and names real-looking packages`() {
        val toolIds = Toolchain.tools.map { it.id }.toSet()
        for (e in Essential.entries) {
            assertTrue(e.id, Regex("^[a-z0-9]+$").matches(e.id))
            e.tools.forEach { assertTrue("${e.id}: $it is a Toolchain tool", it in toolIds) }
            e.packages.forEach { assertTrue("${e.id}: $it", Regex("^[a-z0-9][a-z0-9.+-]*$").matches(it)) }
            if (e != Essential.Agents) assertTrue("${e.id} installs something", e.packages.isNotEmpty() && e.tools.isNotEmpty())
        }
        // The same packages the developer tools setup and the Python pack install, so either path gives the same result.
        val dev = listOf(Essential.Git, Essential.GitHubCli, Essential.Node, Essential.Ripgrep).flatMap { it.packages }
        assertTrue(Toolchain.DEV_PACKAGES.containsAll(dev))
        assertEquals(ToolSetup.Python.packages.toSet(), listOf(Essential.Python, Essential.Pip, Essential.Uv).flatMap { it.packages }.toSet())
    }

    @Test fun `choosing an item brings what it needs, in display order`() {
        assertEquals(listOf(Essential.Python, Essential.Pip), Essential.closure(setOf(Essential.Pip)))
        assertEquals(
            listOf(Essential.Git, Essential.GitHubCli, Essential.Node, Essential.Ripgrep, Essential.Agents),
            Essential.closure(setOf(Essential.Agents)),
        )
        assertEquals(listOf(Essential.Pip, Essential.Uv), Essential.neededBy(Essential.Python, listOf(Essential.Uv, Essential.Pip, Essential.Git)))
        assertTrue(Essential.neededBy(Essential.Python, listOf(Essential.Python)).isEmpty())
        assertEquals(listOf(ToolSetup.Ubuntu, ToolSetup.Flutter), ToolPacks.closure(setOf(ToolSetup.Flutter)))
        assertEquals(listOf(ToolSetup.Flutter), ToolPacks.neededBy(ToolSetup.Ubuntu, listOf(ToolSetup.Flutter, ToolSetup.Go)))
    }

    @Test fun `every optional pack is offered once, and each can tell whether it's installed`() {
        val optional = ToolSetup.entries.filter { it.optional }
        assertEquals("all but Python, which the essentials cover", optional - ToolSetup.Python, ToolPacks.offered - ToolSetup.AndroidSdk)
        assertTrue(ToolSetup.AndroidSdk in ToolPacks.offered)
        for (p in ToolPacks.offered) {
            assertTrue("${p.name} has checks or a marker", ToolPacks.checkIds(p).isNotEmpty() || p.marker != null)
            assertTrue("${p.name} says what it is", ToolPacks.blurb(p).isNotBlank())
            assertTrue("${p.name} says how big it is", ToolPacks.spaceMb(p) > 0)
        }
        assertEquals(listOf("tool-go"), ToolPacks.checkIds(ToolSetup.Go))
        assertEquals(listOf("android-sdk"), ToolPacks.checkIds(ToolSetup.AndroidSdk))
        assertFalse(ToolPacks.joinsPackageRun(ToolSetup.Ubuntu))
        assertFalse(ToolPacks.joinsPackageRun(ToolSetup.AndroidSdk))
        assertTrue(ToolPacks.joinsPackageRun(ToolSetup.Rust))
    }

    @Test fun `every offered pack sits under a heading, and every heading has packs`() {
        for (p in ToolPacks.offered) assertTrue("${p.name} has a category", ToolPacks.category(p) != null)
        for (p in ToolSetup.entries - ToolPacks.offered.toSet()) assertEquals("${p.name} isn't offered", null, ToolPacks.category(p))
        assertEquals(PackCategory.entries.toSet(), ToolPacks.offered.mapNotNull(ToolPacks::category).toSet())
        assertEquals(PackCategory.Environments, ToolPacks.category(ToolSetup.Flutter))
        assertEquals(PackCategory.Languages, ToolPacks.category(ToolSetup.Rust))
    }

    @Test fun `status reads System checks, and waits until they've run`() {
        val checked = setOf("tool-node", "tool-npm", "tool-go", "agents")
        fun of(vararg ids: String, passing: Set<String> = emptySet(), failing: Set<String> = emptySet()) =
            InstallStatus.of(ids.toList(), checked, passing, failing)
        assertEquals(InstallStatus.Checking, of("tool-git"))
        assertEquals(InstallStatus.Checking, of())
        assertEquals(InstallStatus.Installed, of("tool-node", "tool-npm", passing = setOf("tool-node", "tool-npm")))
        assertEquals(InstallStatus.Partial, of("tool-node", "tool-npm", passing = setOf("tool-node")))
        assertEquals(InstallStatus.Broken, of("tool-node", "tool-npm", passing = setOf("tool-node"), failing = setOf("tool-npm")))
        assertEquals(InstallStatus.Missing, of("agents"))
        assertEquals(listOf("agents"), Essential.Agents.checkIds)
        assertEquals(listOf("tool-node", "tool-npm"), Essential.Node.checkIds)
    }

    @Test fun `essentials and package-only packs share one package run`() {
        assertEquals("bash -n $essentials && bash $essentials python pip", installToolsCommand(setOf(Essential.Pip), emptySet()))
        assertEquals(
            "bash -n $essentials && bash $essentials git gh node rg python pip uv sqlite",
            installToolsCommand(Essential.entries - Essential.Agents, emptySet()),
        )
        assertEquals(
            "bash -n $essentials && bash $essentials git GO RUST",
            installToolsCommand(setOf(Essential.Git), setOf(ToolSetup.Rust, ToolSetup.Go)),
        )
    }

    @Test fun `setups with their own script run one after another, each even if one before failed`() {
        assertEquals("bash -n $agents && bash $agents", installToolsCommand(setOf(Essential.Agents, Essential.Git), emptySet()))
        val all = installToolsCommand(Essential.entries, setOf(ToolSetup.Go, ToolSetup.Flutter, ToolSetup.AndroidSdk))
        val orNext = "|| { c=\$?; [ \$c -ge 129 ] && exit \$c; r=1; };"
        assertEquals(
            "bash -n $essentials && bash -n $agents && bash -n ${path("ubuntu-setup.sh")} && bash -n ${path("flutter-setup.sh")} && " +
                "bash -n ${path("android-sdk-setup.sh")} && { r=0; bash $essentials python pip uv sqlite GO $orNext bash $agents $orNext " +
                "bash ${path("ubuntu-setup.sh")} $orNext bash ${path("flutter-setup.sh")} $orNext " +
                "bash ${path("android-sdk-setup.sh")} $orNext [ \$r -eq 0 ]; }",
            all,
        )
        assertEquals("the SDK's free-space need matches its script", 1500, ToolPacks.spaceMb(ToolSetup.AndroidSdk))
        assertTrue("setups get the battery warning", dev.yurlungr.app.ui.Feedback.isHeavy(all))
        assertEquals("bash -n ${path("browser-setup.sh")} && bash ${path("browser-setup.sh")}", installToolsCommand(emptySet(), setOf(ToolSetup.Browser)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nothing chosen is refused`() {
        installToolsCommand(emptySet(), emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setups the sheet doesn't offer are refused`() {
        installToolsCommand(emptySet(), setOf(ToolSetup.DevTools))
    }

    @Test fun `toolchain sh carries each item's packages, tools and checks`() {
        val sh = Toolchain.shellManifest()
        assertTrue(sh.contains("declare -gA YURLUNGR_ESSENTIAL_PACKAGES=("))
        assertTrue(sh.contains("  [node]='nodejs-lts termux-exec'"))
        assertTrue(sh.contains("  [pip]='python-pip'"))
        assertTrue(sh.contains("  [node]='node npm'"))
        assertTrue(sh.contains("YURLUNGR_CHECKS_ESSENTIALS=("))
        assertTrue(sh.contains("'sqlite3\tsqlite3 --version'"))
        assertFalse("agents have no packages here", sh.contains("[agents]="))
        // Packs passed by name find their packages, space and checks under that name.
        assertTrue(sh.contains("YURLUNGR_PACKAGES_GO=(golang)"))
        assertTrue(sh.contains("YURLUNGR_SPACE_MB_GO=700"))
        assertTrue(sh.contains("YURLUNGR_CHECKS_GO=("))
        assertTrue(sh.contains("  ['go-setup.sh']=GO"))
    }

    @Test fun `the setup script is installed and reads what the app writes`() {
        val script = File("src/main/assets/essentials-setup.sh").readText()
        assertTrue(script.contains("setup_init \"\$HOME/essentials-setup.log\""))
        for (name in listOf("YURLUNGR_ESSENTIAL_PACKAGES", "YURLUNGR_ESSENTIAL_TOOLS", "YURLUNGR_CHECKS_ESSENTIALS", "YURLUNGR_PACKAGES_", "YURLUNGR_CHECKS_", "YURLUNGR_PACK_OF_SCRIPT")) {
            assertTrue(name, script.contains(name))
        }
        val assets = File("src/main/java/dev/yurlungr/app/runtime/RuntimeAssets.kt").readText()
        assertTrue(assets.contains("\"essentials-setup.sh\" to File(paths.libexec, \"essentials-setup.sh\")"))
    }
}
