package dev.yurlungr.app.projects

import dev.yurlungr.app.projects.Git.Companion.shellQuote
import dev.yurlungr.app.runtime.ToolSetup
import dev.yurlungr.app.runtime.projectSetupCommand
import java.io.File

/**
 * Starter projects for New project. Each one is a small set of files written by the app
 * (deterministic, no scaffolder whose native binaries break on Android), except where the
 * framework's own CLI is the only sane way (Laravel, Expo, Flutter): those run as [scaffold] steps.
 * Dependencies then install through the usual stack setup, and [server] tells Run how to start
 * stacks the web detector doesn't know (Python, Go, Rust, PHP, Flutter).
 */
internal data class ProjectTemplate(
    val id: String,
    val name: String,
    val blurb: String,
    val group: TemplateGroup,
    val requires: List<Requirement>,
    val experimental: Boolean = false,
    /** Lines for .gitignore. */
    val ignore: List<String>,
    /** Project files by relative path. */
    val files: (TemplateNames) -> Map<String, String> = { emptyMap() },
    /** Termux packages and steps that generate the project instead of (or after) [files]. */
    val scaffoldPackages: List<String> = emptyList(),
    val scaffold: (TemplateNames) -> List<String> = { emptyList() },
    val server: ServerRun? = null,
    val guide: TemplateGuide? = null,
) {
    val isEmpty get() = id == "empty"
}

internal enum class TemplateGroup(val label: String) { Start("Start"), Web("Web"), Api("API & back-end"), Mobile("Mobile") }

/** A tool that has to be installed before the template can work: [marker] is relative to the prefix. */
internal data class Requirement(val label: String, val marker: String, val pack: ToolSetup?, val packTitle: String)

/** A dev server the web detector can't find: its command reads $PORT and binds 127.0.0.1. */
internal data class ServerRun(val framework: String, val command: String, val startupTimeoutSeconds: Int = 180)

/** What AGENTS.md says about the stack. */
internal data class TemplateGuide(
    val stack: List<String>,
    val runCommand: String,
    val testCommand: String?,
    val installCommand: String?,
    val notes: List<String> = emptyList(),
)

/** The names a template needs, derived once from the folder name. */
internal data class TemplateNames(val folder: String, val slug: String, val crate: String, val dart: String, val secret: String = "dev-only-change-me") {
    companion object {
        fun of(folder: String, secret: String = "dev-only-change-me") =
            TemplateNames(folder, Templates.slug(folder), Templates.crateName(folder), Templates.dartName(folder), secret)
    }
}

internal object Templates {
    /** Where the CLI-scaffolded templates generate before moving into the (non-empty) project folder. */
    const val SCAFFOLD_DIR = ".yurlungr-scaffold"

    private val NODE = Requirement("Node.js", "bin/node", ToolSetup.DevTools, "Developer tools")
    private val PYTHON = Requirement("Python", ToolSetup.Python.marker!!, ToolSetup.Python, ToolSetup.Python.title)
    private val GO = Requirement("Go", ToolSetup.Go.marker!!, ToolSetup.Go, ToolSetup.Go.title)
    private val RUST = Requirement("Rust", ToolSetup.Rust.marker!!, ToolSetup.Rust, ToolSetup.Rust.title)
    private val PHP = Requirement("PHP and Composer", ToolSetup.Php.marker!!, ToolSetup.Php, ToolSetup.Php.title)
    private val UBUNTU = Requirement("Ubuntu environment", "bin/yurlungr-ubuntu", null, "Ubuntu environment")
    private val FLUTTER = Requirement("Flutter SDK", "var/lib/proot-distro/installed-rootfs/ubuntu/opt/flutter/bin/flutter", null, "Flutter (experimental)")

    /** npm-style package name: lowercase letters, digits and hyphens. */
    fun slug(folder: String): String =
        folder.lowercase().replace(Regex("[^a-z0-9-]+"), "-").replace(Regex("-+"), "-").trim('-').ifEmpty { "app" }

    /** Cargo package names must start with a letter. */
    fun crateName(folder: String): String = slug(folder).let { if (it.first().isLetter()) it else "app-$it" }

    /** Dart package names: lowercase_with_underscores, starting with a letter. */
    fun dartName(folder: String): String =
        slug(folder).replace('-', '_').let { if (it.first().isLetter()) it else "app_$it" }

    /** The tools [template] needs that aren't under [prefix]. */
    fun missing(template: ProjectTemplate, prefix: File): List<Requirement> = template.requires.filterNot { File(prefix, it.marker).exists() }

    /** "Install Python and Rust in System checks first." */
    fun missingMessage(missing: List<Requirement>): String? {
        val titles = missing.map { it.packTitle }.distinct().takeIf { it.isNotEmpty() } ?: return null
        val list = if (titles.size == 1) titles[0] else titles.dropLast(1).joinToString() + " and " + titles.last()
        return "Install $list in System checks first."
    }

    /** Every file the template writes: its own, plus .gitignore and AGENTS.md. */
    fun allFiles(template: ProjectTemplate, names: TemplateNames): Map<String, String> = buildMap {
        putAll(template.files(names))
        put(".gitignore", "# Added by Yurlungr\n" + template.ignore.joinToString("\n") + "\n")
        put("AGENTS.md", agentsMd(template, names))
    }

    /**
     * The terminal command that makes the new project runnable: the template's own scaffold steps,
     * else installing what the stack detector found in the written files. Null: nothing to install.
     */
    fun setupCommand(template: ProjectTemplate, dir: File, names: TemplateNames, stacks: List<DetectedStack>): String? {
        val steps = template.scaffold(names)
        if (steps.isNotEmpty()) return projectSetupCommand(dir, template.scaffoldPackages, steps)
        val installable = stacks.filter { it.canInstall }
        return if (installable.isEmpty()) null else installCommand(dir, installable)
    }

    fun agentsMd(template: ProjectTemplate, names: TemplateNames): String = buildString {
        val g = template.guide
        append("# ").append(names.folder).append("\n\n")
        append(template.blurb.trimEnd('.')).append(". Created from the Yurlungr \"").append(template.name).append("\" template")
        append(if (template.experimental) " (experimental).\n\n" else ".\n\n")
        if (g == null) return@buildString
        append("## Stack\n\n")
        g.stack.forEach { append("- ").append(it).append('\n') }
        append("\n## Run\n\n")
        append("- In Yurlungr: open the project and tap **Run**. The app starts the dev server on a free port, waits until it answers and opens the preview.\n")
        append("- From a terminal: `").append(g.runCommand).append("`\n")
        append("- Servers listen on 127.0.0.1 and take their port from `\$PORT` (Run sets it and proxies to it). Keep it that way when editing the server code.\n")
        g.installCommand?.let { append("\n## Install dependencies\n\n- `").append(it).append("` (Project setup in the app runs this for you)\n") }
        g.testCommand?.let { append("\n## Test\n\n- `").append(it).append("`\n") }
        append("\n## Notes for this phone\n\n")
        append("- This runs on an Android phone, in a Termux-based Linux environment (not glibc Linux): prebuilt native binaries for linux-x64/arm64-gnu don't load. Prefer pure JavaScript/Python packages or ones with Android builds.\n")
        append("- Storage, memory and battery are limited: avoid huge dependency trees and long watch processes you don't need.\n")
        g.notes.forEach { append("- ").append(it).append('\n') }
    }

    // ---- File contents ----------------------------------------------------------------------

    private fun html(title: String, body: String, head: String = "") = """
        |<!doctype html>
        |<html lang="en">
        |  <head>
        |    <meta charset="UTF-8" />
        |    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        |    <title>$title</title>
        |$head  </head>
        |  <body>
        |$body  </body>
        |</html>
        |""".trimMargin()

    private const val CSS = """:root {
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  color-scheme: light dark;
}
body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
}
main {
  padding: 24px;
  text-align: center;
}
button {
  font: inherit;
  padding: 10px 18px;
  border-radius: 12px;
  border: 1px solid currentColor;
  background: transparent;
  cursor: pointer;
}
"""

    private fun packageJson(slug: String, scripts: List<Pair<String, String>>, deps: List<Pair<String, String>>, devDeps: List<Pair<String, String>> = emptyList(), module: Boolean = true): String {
        fun obj(entries: List<Pair<String, String>>) = entries.joinToString(",\n") { (k, v) -> "    \"$k\": \"${v.replace("\"", "\\\"")}\"" }
        return buildString {
            append("{\n  \"name\": \"").append(slug).append("\",\n  \"private\": true,\n  \"version\": \"0.1.0\",\n")
            if (module) append("  \"type\": \"module\",\n")
            append("  \"scripts\": {\n").append(obj(scripts)).append("\n  }")
            if (deps.isNotEmpty()) append(",\n  \"dependencies\": {\n").append(obj(deps)).append("\n  }")
            if (devDeps.isNotEmpty()) append(",\n  \"devDependencies\": {\n").append(obj(devDeps)).append("\n  }")
            append("\n}\n")
        }
    }

    private const val VITE_TSCONFIG_BASE = """    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "types": ["vite/client"]"""

    private val NODE_IGNORE = listOf("node_modules/", "dist/", ".env", ".env.*", "!.env.example", "*.log", ".DS_Store")
    private val PY_IGNORE = listOf(".venv/", "__pycache__/", "*.pyc", ".pytest_cache/", ".env", "*.sqlite3", ".DS_Store")

    private val viteNotes = listOf(
        "Vite uses esbuild and Rollup, which ship Android builds; if a plugin needs a native binary that has none, look for a WASM or pure-JS alternative.",
        "Keep `vite` as the `dev` script: Run adds `--host 127.0.0.1 --port <port>` itself.",
    )

    val all: List<ProjectTemplate> = listOf(
        ProjectTemplate("empty", "Empty", "An empty folder for your next idea", TemplateGroup.Start, emptyList(), ignore = emptyList()),

        ProjectTemplate(
            "static", "HTML, CSS & JS", "A static site: plain HTML, CSS and JavaScript, no build step", TemplateGroup.Start, listOf(NODE),
            ignore = listOf(".DS_Store"),
            files = { n ->
                mapOf(
                    "index.html" to html(n.folder, "    <main>\n      <h1>${n.folder}</h1>\n      <p>Edit index.html, style.css and script.js.</p>\n      <button id=\"count\">Clicked 0 times</button>\n    </main>\n    <script src=\"script.js\"></script>\n",
                        head = "    <link rel=\"stylesheet\" href=\"style.css\" />\n"),
                    "style.css" to CSS,
                    "script.js" to "let clicks = 0;\nconst button = document.getElementById(\"count\");\nbutton.addEventListener(\"click\", () => {\n  clicks += 1;\n  button.textContent = \"Clicked \" + clicks + \" times\";\n});\n",
                )
            },
            guide = TemplateGuide(
                listOf("Plain HTML, CSS and JavaScript (no build step, no dependencies)"),
                runCommand = "npx --yes serve -l tcp://127.0.0.1:8080 .", testCommand = null, installCommand = null,
                notes = listOf("Run serves the folder as it is; reload the preview after editing."),
            ),
        ),

        ProjectTemplate(
            "vite-react", "Vite + React", "React 19 with TypeScript, built and served by Vite", TemplateGroup.Web, listOf(NODE),
            ignore = NODE_IGNORE,
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "vite", "build" to "tsc --noEmit && vite build", "preview" to "vite preview", "typecheck" to "tsc --noEmit"),
                        listOf("react" to "^19.1.0", "react-dom" to "^19.1.0"),
                        listOf("@types/react" to "^19.1.0", "@types/react-dom" to "^19.1.0", "@vitejs/plugin-react" to "^5.0.0", "typescript" to "~5.9.2", "vite" to "^7.1.0")),
                    "tsconfig.json" to "{\n  \"compilerOptions\": {\n$VITE_TSCONFIG_BASE,\n    \"jsx\": \"react-jsx\"\n  },\n  \"include\": [\"src\"]\n}\n",
                    "vite.config.ts" to "import { defineConfig } from \"vite\";\nimport react from \"@vitejs/plugin-react\";\n\nexport default defineConfig({\n  plugins: [react()],\n});\n",
                    "index.html" to html(n.folder, "    <div id=\"root\"></div>\n    <script type=\"module\" src=\"/src/main.tsx\"></script>\n"),
                    "src/main.tsx" to "import { StrictMode } from \"react\";\nimport { createRoot } from \"react-dom/client\";\nimport App from \"./App\";\nimport \"./index.css\";\n\ncreateRoot(document.getElementById(\"root\")!).render(\n  <StrictMode>\n    <App />\n  </StrictMode>,\n);\n",
                    "src/App.tsx" to "import { useState } from \"react\";\n\nexport default function App() {\n  const [count, setCount] = useState(0);\n  return (\n    <main>\n      <h1>${n.folder}</h1>\n      <p>Edit src/App.tsx and save: the preview updates.</p>\n      <button onClick={() => setCount((c) => c + 1)}>Clicked {count} times</button>\n    </main>\n  );\n}\n",
                    "src/index.css" to CSS,
                )
            },
            guide = TemplateGuide(listOf("React 19, TypeScript", "Vite 7 (dev server and build)", "npm"), "npm run dev", "npm run typecheck && npm run build", "npm install", viteNotes),
        ),

        ProjectTemplate(
            "vite-vue", "Vite + Vue", "Vue 3 with TypeScript, built and served by Vite", TemplateGroup.Web, listOf(NODE),
            ignore = NODE_IGNORE,
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "vite", "build" to "vite build", "preview" to "vite preview"),
                        listOf("vue" to "^3.5.0"),
                        listOf("@vitejs/plugin-vue" to "^6.0.0", "typescript" to "~5.9.2", "vite" to "^7.1.0")),
                    "tsconfig.json" to "{\n  \"compilerOptions\": {\n$VITE_TSCONFIG_BASE,\n    \"jsx\": \"preserve\"\n  },\n  \"include\": [\"src/**/*.ts\", \"src/**/*.vue\"]\n}\n",
                    "vite.config.ts" to "import { defineConfig } from \"vite\";\nimport vue from \"@vitejs/plugin-vue\";\n\nexport default defineConfig({\n  plugins: [vue()],\n});\n",
                    "index.html" to html(n.folder, "    <div id=\"app\"></div>\n    <script type=\"module\" src=\"/src/main.ts\"></script>\n"),
                    "src/main.ts" to "import { createApp } from \"vue\";\nimport App from \"./App.vue\";\nimport \"./style.css\";\n\ncreateApp(App).mount(\"#app\");\n",
                    "src/env.d.ts" to "/// <reference types=\"vite/client\" />\n\ndeclare module \"*.vue\" {\n  import type { DefineComponent } from \"vue\";\n  const component: DefineComponent<object, object, unknown>;\n  export default component;\n}\n",
                    "src/App.vue" to "<script setup lang=\"ts\">\nimport { ref } from \"vue\";\n\nconst count = ref(0);\n</script>\n\n<template>\n  <main>\n    <h1>${n.folder}</h1>\n    <p>Edit src/App.vue and save: the preview updates.</p>\n    <button @click=\"count++\">Clicked {{ count }} times</button>\n  </main>\n</template>\n",
                    "src/style.css" to CSS,
                )
            },
            guide = TemplateGuide(listOf("Vue 3 (single-file components), TypeScript", "Vite 7 (dev server and build)", "npm"), "npm run dev", "npm run build", "npm install",
                viteNotes + "Type-checking .vue files needs vue-tsc (`npm i -D vue-tsc`, then `npx vue-tsc --noEmit`)."),
        ),

        ProjectTemplate(
            "vite-svelte", "Vite + Svelte", "Svelte 5 with TypeScript, built and served by Vite", TemplateGroup.Web, listOf(NODE),
            ignore = NODE_IGNORE,
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "vite", "build" to "vite build", "preview" to "vite preview"),
                        emptyList(),
                        listOf("@sveltejs/vite-plugin-svelte" to "^6.1.0", "svelte" to "^5.38.0", "typescript" to "~5.9.2", "vite" to "^7.1.0")),
                    "tsconfig.json" to "{\n  \"compilerOptions\": {\n$VITE_TSCONFIG_BASE,\n    \"verbatimModuleSyntax\": true\n  },\n  \"include\": [\"src/**/*.ts\", \"src/**/*.svelte\"]\n}\n",
                    "vite.config.ts" to "import { defineConfig } from \"vite\";\nimport { svelte } from \"@sveltejs/vite-plugin-svelte\";\n\nexport default defineConfig({\n  plugins: [svelte()],\n});\n",
                    "svelte.config.js" to "import { vitePreprocess } from \"@sveltejs/vite-plugin-svelte\";\n\nexport default {\n  preprocess: vitePreprocess(),\n};\n",
                    "index.html" to html(n.folder, "    <div id=\"app\"></div>\n    <script type=\"module\" src=\"/src/main.ts\"></script>\n"),
                    "src/main.ts" to "import { mount } from \"svelte\";\nimport App from \"./App.svelte\";\nimport \"./app.css\";\n\nexport default mount(App, { target: document.getElementById(\"app\")! });\n",
                    "src/vite-env.d.ts" to "/// <reference types=\"svelte\" />\n/// <reference types=\"vite/client\" />\n",
                    "src/App.svelte" to "<script lang=\"ts\">\n  let count = \$state(0);\n</script>\n\n<main>\n  <h1>${n.folder}</h1>\n  <p>Edit src/App.svelte and save: the preview updates.</p>\n  <button onclick={() => count++}>Clicked {count} times</button>\n</main>\n",
                    "src/app.css" to CSS,
                )
            },
            guide = TemplateGuide(listOf("Svelte 5 (runes), TypeScript", "Vite 7 (dev server and build)", "npm"), "npm run dev", "npm run build", "npm install",
                viteNotes + "Type-checking .svelte files needs svelte-check (`npm i -D svelte-check`, then `npx svelte-check`)."),
        ),

        ProjectTemplate(
            "next", "Next.js", "Next.js 15 with the App Router (JavaScript)", TemplateGroup.Web, listOf(NODE),
            ignore = NODE_IGNORE + listOf(".next/", "out/", "next-env.d.ts"),
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "next dev", "build" to "next build", "start" to "next start"),
                        listOf("next" to "^15.5.0", "react" to "^19.1.0", "react-dom" to "^19.1.0")),
                    "next.config.mjs" to "/** @type {import('next').NextConfig} */\nconst nextConfig = {};\n\nexport default nextConfig;\n",
                    "jsconfig.json" to "{\n  \"compilerOptions\": {\n    \"paths\": { \"@/*\": [\"./*\"] }\n  }\n}\n",
                    "app/layout.jsx" to "import \"./globals.css\";\n\nexport const metadata = {\n  title: \"${n.folder}\",\n};\n\nexport default function RootLayout({ children }) {\n  return (\n    <html lang=\"en\">\n      <body>{children}</body>\n    </html>\n  );\n}\n",
                    "app/page.jsx" to "export default function Home() {\n  return (\n    <main>\n      <h1>${n.folder}</h1>\n      <p>Edit app/page.jsx and save: the preview updates.</p>\n    </main>\n  );\n}\n",
                    "app/api/health/route.js" to "export function GET() {\n  return Response.json({ ok: true });\n}\n",
                    "app/globals.css" to CSS,
                )
            },
            guide = TemplateGuide(listOf("Next.js 15 (App Router), React 19, JavaScript", "npm"), "npm run dev", "npm run build", "npm install", listOf(
                "Next.js's native compiler (SWC) has no Android build: Run uses its WASM fallback and Webpack (Compatibility profile). The first compile is slow; later ones are faster.",
                "Turbopack (`next dev --turbopack`) can't run here: keep the `dev` script as `next dev`.",
                "Adding TypeScript later: create tsconfig.json and install typescript, @types/react and @types/node yourself (Next.js tries to install them on its own).",
            )),
        ),

        ProjectTemplate(
            "astro", "Astro", "A content-first site with Astro 5", TemplateGroup.Web, listOf(NODE),
            ignore = NODE_IGNORE + listOf(".astro/"),
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "astro dev", "build" to "astro build", "preview" to "astro preview", "check" to "astro check"),
                        listOf("astro" to "^5.13.0")),
                    "astro.config.mjs" to "import { defineConfig } from \"astro/config\";\n\nexport default defineConfig({});\n",
                    "tsconfig.json" to "{\n  \"extends\": \"astro/tsconfigs/strict\",\n  \"include\": [\".astro/types.d.ts\", \"**/*\"],\n  \"exclude\": [\"dist\"]\n}\n",
                    "src/pages/index.astro" to "---\nconst title = \"${n.folder}\";\n---\n\n<html lang=\"en\">\n  <head>\n    <meta charset=\"utf-8\" />\n    <meta name=\"viewport\" content=\"width=device-width\" />\n    <title>{title}</title>\n  </head>\n  <body>\n    <main>\n      <h1>{title}</h1>\n      <p>Edit src/pages/index.astro and save: the preview updates.</p>\n    </main>\n  </body>\n</html>\n\n<style>\n  main {\n    font-family: system-ui, sans-serif;\n    padding: 24px;\n    text-align: center;\n  }\n</style>\n",
                )
            },
            guide = TemplateGuide(listOf("Astro 5 (static pages, islands when needed)", "npm"), "npm run dev", "npm run build", "npm install", listOf(
                "Keep `astro dev` as the `dev` script: Run adds the host and port.",
                "`astro check` needs @astrojs/check and typescript (`npm i -D @astrojs/check typescript`).",
            )),
        ),

        ProjectTemplate(
            "express", "Express", "A Node.js HTTP API with Express 5", TemplateGroup.Api, listOf(NODE),
            ignore = NODE_IGNORE,
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "node --watch server.js", "start" to "node server.js", "test" to "node --test"),
                        listOf("express" to "^5.1.0")),
                    "app.js" to "import express from \"express\";\n\nexport const app = express();\napp.use(express.json());\n\napp.get(\"/\", (req, res) => {\n  res.type(\"html\").send(\"<h1>${n.folder}</h1><p>Express is running. Try <a href=\\\"/api/health\\\">/api/health</a>.</p>\");\n});\n\napp.get(\"/api/health\", (req, res) => {\n  res.json({ ok: true });\n});\n",
                    "server.js" to "import { app } from \"./app.js\";\n\n// Run sets PORT and proxies to 127.0.0.1: keep both.\nconst port = Number(process.env.PORT) || 3000;\nconst host = process.env.HOST || \"127.0.0.1\";\n\napp.listen(port, host, () => {\n  console.log(\"Listening on http://\" + host + \":\" + port);\n});\n",
                    "test/app.test.js" to "import { test } from \"node:test\";\nimport assert from \"node:assert/strict\";\nimport { app } from \"../app.js\";\n\ntest(\"health route answers\", async () => {\n  const server = app.listen(0, \"127.0.0.1\");\n  await new Promise((resolve) => server.once(\"listening\", resolve));\n  try {\n    const res = await fetch(\"http://127.0.0.1:\" + server.address().port + \"/api/health\");\n    assert.equal(res.status, 200);\n    assert.deepEqual(await res.json(), { ok: true });\n  } finally {\n    server.close();\n  }\n});\n",
                )
            },
            guide = TemplateGuide(listOf("Node.js, Express 5, ES modules", "Tests: node:test (built in)", "npm"), "npm run dev", "npm test", "npm install",
                listOf("`npm run dev` restarts on save with `node --watch`.")),
        ),

        ProjectTemplate(
            "hono", "Hono", "A small, fast Node.js API with Hono", TemplateGroup.Api, listOf(NODE),
            ignore = NODE_IGNORE,
            files = { n ->
                mapOf(
                    "package.json" to packageJson(n.slug,
                        listOf("dev" to "node --watch src/index.js", "start" to "node src/index.js", "test" to "node --test"),
                        listOf("hono" to "^4.9.0", "@hono/node-server" to "^1.19.0")),
                    "src/app.js" to "import { Hono } from \"hono\";\n\nexport const app = new Hono();\n\napp.get(\"/\", (c) => c.html(\"<h1>${n.folder}</h1><p>Hono is running. Try <a href=\\\"/api/health\\\">/api/health</a>.</p>\"));\n\napp.get(\"/api/health\", (c) => c.json({ ok: true }));\n",
                    "src/index.js" to "import { serve } from \"@hono/node-server\";\nimport { app } from \"./app.js\";\n\n// Run sets PORT and proxies to 127.0.0.1: keep both.\nconst port = Number(process.env.PORT) || 3000;\nconst hostname = process.env.HOST || \"127.0.0.1\";\n\nserve({ fetch: app.fetch, port, hostname }, (info) => {\n  console.log(\"Listening on http://\" + hostname + \":\" + info.port);\n});\n",
                    "test/app.test.js" to "import { test } from \"node:test\";\nimport assert from \"node:assert/strict\";\nimport { app } from \"../src/app.js\";\n\ntest(\"health route answers\", async () => {\n  const res = await app.request(\"/api/health\");\n  assert.equal(res.status, 200);\n  assert.deepEqual(await res.json(), { ok: true });\n});\n",
                )
            },
            guide = TemplateGuide(listOf("Node.js, Hono 4 with @hono/node-server, ES modules", "Tests: node:test (built in), using app.request()", "npm"), "npm run dev", "npm test", "npm install",
                listOf("`npm run dev` restarts on save with `node --watch`.")),
        ),

        ProjectTemplate(
            "fastapi", "FastAPI", "A Python API with FastAPI and Uvicorn", TemplateGroup.Api, listOf(NODE, PYTHON, RUST),
            ignore = PY_IGNORE,
            files = { n ->
                mapOf(
                    "requirements.txt" to "fastapi>=0.115,<1\nuvicorn>=0.30\nhttpx>=0.27\npytest>=8\n",
                    "main.py" to "from fastapi import FastAPI\nfrom fastapi.responses import HTMLResponse\n\napp = FastAPI(title=\"${n.folder}\")\n\n\n@app.get(\"/\", response_class=HTMLResponse)\ndef index() -> str:\n    return \"<h1>${n.folder}</h1><p>FastAPI is running. API docs: <a href='/docs'>/docs</a></p>\"\n\n\n@app.get(\"/api/health\")\ndef health() -> dict:\n    return {\"ok\": True}\n",
                    "test_main.py" to "from fastapi.testclient import TestClient\n\nfrom main import app\n\nclient = TestClient(app)\n\n\ndef test_health():\n    response = client.get(\"/api/health\")\n    assert response.status_code == 200\n    assert response.json() == {\"ok\": True}\n",
                )
            },
            server = ServerRun("FastAPI", ".venv/bin/uvicorn main:app --host 127.0.0.1 --port \"\$PORT\" --reload", startupTimeoutSeconds = 300),
            guide = TemplateGuide(listOf("Python 3, FastAPI, Uvicorn", "Tests: pytest with FastAPI's TestClient", "Virtual environment in .venv"),
                ".venv/bin/uvicorn main:app --host 127.0.0.1 --port \"\${PORT:-8000}\" --reload", ".venv/bin/pytest",
                "python -m venv --system-site-packages .venv && .venv/bin/pip install -r requirements.txt", listOf(
                    "pydantic-core (used by FastAPI) has no prebuilt wheel for this environment: the first install compiles it with Rust, which takes several minutes.",
                    "Plain `uvicorn` is used on purpose: `uvicorn[standard]` pulls uvloop and httptools, which need compiling too. `--reload` falls back to polling, which is fine.",
                    "Interactive API docs are at /docs in the preview.",
                )),
        ),

        ProjectTemplate(
            "flask", "Flask", "A Python web app with Flask", TemplateGroup.Api, listOf(NODE, PYTHON),
            ignore = PY_IGNORE + listOf("instance/"),
            files = { n ->
                mapOf(
                    "requirements.txt" to "Flask>=3.1,<4\npytest>=8\n",
                    "app.py" to "from flask import Flask, jsonify\n\napp = Flask(__name__)\n\n\n@app.get(\"/\")\ndef index():\n    return \"<h1>${n.folder}</h1><p>Flask is running. Try <a href='/api/health'>/api/health</a>.</p>\"\n\n\n@app.get(\"/api/health\")\ndef health():\n    return jsonify(ok=True)\n",
                    "test_app.py" to "from app import app\n\n\ndef test_health():\n    client = app.test_client()\n    response = client.get(\"/api/health\")\n    assert response.status_code == 200\n    assert response.get_json() == {\"ok\": True}\n",
                )
            },
            server = ServerRun("Flask", ".venv/bin/flask --app app run --host 127.0.0.1 --port \"\$PORT\" --debug"),
            guide = TemplateGuide(listOf("Python 3, Flask 3", "Tests: pytest with Flask's test client", "Virtual environment in .venv"),
                ".venv/bin/flask --app app run --host 127.0.0.1 --port \"\${PORT:-5000}\" --debug", ".venv/bin/pytest",
                "python -m venv --system-site-packages .venv && .venv/bin/pip install -r requirements.txt",
                listOf("`--debug` reloads the server when you save a file.")),
        ),

        ProjectTemplate(
            "django", "Django", "A Python web app with Django 5.2 (LTS) and SQLite", TemplateGroup.Api, listOf(NODE, PYTHON),
            ignore = PY_IGNORE + listOf("staticfiles/", "media/"),
            files = { n -> djangoFiles(n) },
            server = ServerRun("Django", ".venv/bin/python manage.py migrate --noinput && exec .venv/bin/python manage.py runserver 127.0.0.1:\"\$PORT\"", startupTimeoutSeconds = 240),
            guide = TemplateGuide(listOf("Python 3, Django 5.2 LTS", "SQLite database (db.sqlite3, created by migrate)", "Project package: config/ (settings, urls); add apps with `.venv/bin/python manage.py startapp <name>`", "Virtual environment in .venv"),
                ".venv/bin/python manage.py runserver 127.0.0.1:\"\${PORT:-8000}\"", ".venv/bin/python manage.py test",
                "python -m venv --system-site-packages .venv && .venv/bin/pip install -r requirements.txt", listOf(
                    "Run applies migrations before starting the server.",
                    "settings.py is for development (DEBUG on, a local secret key). Set DJANGO_SECRET_KEY and turn DEBUG off before deploying anywhere.",
                )),
        ),

        ProjectTemplate(
            "laravel", "Laravel", "A PHP web app made by Laravel's own installer", TemplateGroup.Api, listOf(NODE, PHP),
            ignore = listOf("vendor/", "node_modules/", ".env", "public/build/", "public/hot", "storage/*.key", ".phpunit.result.cache", ".DS_Store"),
            scaffoldPackages = listOf("php", "composer", "nodejs-lts"),
            scaffold = { _ ->
                listOf(
                    "rm -rf $SCAFFOLD_DIR && composer create-project --no-interaction --prefer-dist laravel/laravel $SCAFFOLD_DIR",
                    // The installer wants an empty folder; this one already has AGENTS.md (and maybe .git).
                    "cp -a $SCAFFOLD_DIR/. . && rm -rf $SCAFFOLD_DIR",
                    // Front-end assets are optional for the welcome page: a failure here mustn't fail setup.
                    "npm install --no-audit --no-fund || echo 'npm install failed: run it later if you need the Vite assets.'",
                )
            },
            server = ServerRun("Laravel", "php artisan serve --host=127.0.0.1 --port=\"\$PORT\"", startupTimeoutSeconds = 240),
            guide = TemplateGuide(listOf("PHP, Laravel (latest that this PHP supports), Composer", "SQLite database (database/database.sqlite)", "Vite for front-end assets (optional)"),
                "php artisan serve --host=127.0.0.1 --port=\"\${PORT:-8000}\"", "php artisan test", "composer install && npm install", listOf(
                    "Laravel was generated by `composer create-project laravel/laravel`, run on the phone.",
                    "Front-end assets: `npm run dev` runs Vite next to `php artisan serve`; `npm run build` makes static assets so the dev server isn't needed.",
                    "If a PHP extension is missing, install it with `pkg install php-<name>` (Termux packages).",
                )),
        ),

        ProjectTemplate(
            "go", "Go net/http", "A Go HTTP server using only the standard library", TemplateGroup.Api, listOf(NODE, GO),
            ignore = listOf("/bin/", "*.exe", "*.test", "*.out", ".env", ".DS_Store"),
            files = { n ->
                mapOf(
                    "go.mod" to "module ${n.slug}\n\ngo 1.22\n",
                    "main.go" to "package main\n\nimport (\n\t\"encoding/json\"\n\t\"fmt\"\n\t\"log\"\n\t\"net/http\"\n\t\"os\"\n)\n\nfunc newMux() *http.ServeMux {\n\tmux := http.NewServeMux()\n\tmux.HandleFunc(\"GET /{\$}\", func(w http.ResponseWriter, r *http.Request) {\n\t\tw.Header().Set(\"Content-Type\", \"text/html; charset=utf-8\")\n\t\tfmt.Fprint(w, \"<h1>${n.folder}</h1><p>Go is running. Try <a href='/api/health'>/api/health</a>.</p>\")\n\t})\n\tmux.HandleFunc(\"GET /api/health\", func(w http.ResponseWriter, r *http.Request) {\n\t\tw.Header().Set(\"Content-Type\", \"application/json\")\n\t\tjson.NewEncoder(w).Encode(map[string]bool{\"ok\": true})\n\t})\n\treturn mux\n}\n\nfunc main() {\n\t// Run sets PORT and proxies to 127.0.0.1: keep both.\n\tport := os.Getenv(\"PORT\")\n\tif port == \"\" {\n\t\tport = \"8080\"\n\t}\n\taddr := \"127.0.0.1:\" + port\n\tlog.Printf(\"Listening on http://%s\", addr)\n\tlog.Fatal(http.ListenAndServe(addr, newMux()))\n}\n",
                    "main_test.go" to "package main\n\nimport (\n\t\"net/http\"\n\t\"net/http/httptest\"\n\t\"testing\"\n)\n\nfunc TestHealth(t *testing.T) {\n\trec := httptest.NewRecorder()\n\tnewMux().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, \"/api/health\", nil))\n\tif rec.Code != http.StatusOK {\n\t\tt.Fatalf(\"status %d\", rec.Code)\n\t}\n}\n",
                )
            },
            server = ServerRun("Go", "go run .", startupTimeoutSeconds = 600),
            guide = TemplateGuide(listOf("Go (standard library net/http, Go 1.22+ routing patterns)", "Tests: go test"), "go run .", "go test ./...", "go mod download", listOf(
                "The first `go run` compiles everything, which takes a while on a phone; later runs use the build cache.",
                "Run doesn't reload on save: restart it from the project (or the server card) after editing.",
            )),
        ),

        ProjectTemplate(
            "axum", "Rust axum", "A Rust HTTP server with axum and Tokio", TemplateGroup.Api, listOf(NODE, RUST),
            ignore = listOf("/target/", ".env", ".DS_Store"),
            files = { n ->
                mapOf(
                    "Cargo.toml" to "[package]\nname = \"${n.crate}\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\naxum = \"0.8\"\ntokio = { version = \"1\", features = [\"macros\", \"rt-multi-thread\", \"net\"] }\n\n[dev-dependencies]\ntower = { version = \"0.5\", features = [\"util\"] }\n",
                    "src/main.rs" to "use axum::{response::Html, routing::get, Router};\nuse std::net::SocketAddr;\n\nfn app() -> Router {\n    Router::new()\n        .route(\"/\", get(index))\n        .route(\"/api/health\", get(health))\n}\n\nasync fn index() -> Html<&'static str> {\n    Html(\"<h1>${n.folder}</h1><p>axum is running. Try <a href='/api/health'>/api/health</a>.</p>\")\n}\n\nasync fn health() -> &'static str {\n    \"ok\"\n}\n\n#[tokio::main]\nasync fn main() {\n    // Run sets PORT and proxies to 127.0.0.1: keep both.\n    let port: u16 = std::env::var(\"PORT\").ok().and_then(|p| p.parse().ok()).unwrap_or(3000);\n    let addr = SocketAddr::from(([127, 0, 0, 1], port));\n    let listener = tokio::net::TcpListener::bind(addr).await.expect(\"bind\");\n    println!(\"Listening on http://{addr}\");\n    axum::serve(listener, app()).await.expect(\"server\");\n}\n\n#[cfg(test)]\nmod tests {\n    use super::app;\n    use axum::{body::Body, http::{Request, StatusCode}};\n    use tower::ServiceExt;\n\n    #[tokio::test]\n    async fn health_route_answers() {\n        let response = app()\n            .oneshot(Request::builder().uri(\"/api/health\").body(Body::empty()).unwrap())\n            .await\n            .unwrap();\n        assert_eq!(response.status(), StatusCode::OK);\n    }\n}\n",
                )
            },
            server = ServerRun("Rust axum", "cargo run", startupTimeoutSeconds = 900),
            guide = TemplateGuide(listOf("Rust (2021 edition), axum 0.8, Tokio", "Tests: cargo test"), "cargo run", "cargo test", "cargo fetch", listOf(
                "The first build compiles every dependency and can take 10+ minutes on a phone; Run waits up to 15 minutes. Later builds are incremental.",
                "Run doesn't reload on save: restart it after editing.",
            )),
        ),

        ProjectTemplate(
            "expo", "Expo", "A React Native app with Expo, for Expo Go and the web", TemplateGroup.Mobile, listOf(NODE),
            ignore = NODE_IGNORE + listOf(".expo/", "web-build/", "dist/"),
            scaffoldPackages = listOf("nodejs-lts"),
            scaffold = { n ->
                val tmp = "$SCAFFOLD_DIR/${n.slug}"
                listOf(
                    // The template that matches the current Expo Go, so the app opens in it.
                    "rm -rf $SCAFFOLD_DIR && npx --yes create-expo-app@latest $tmp --template blank --no-install",
                    "rm -rf $tmp/.git && cp -a $tmp/. . && rm -rf $SCAFFOLD_DIR",
                    "npm install --no-audit --no-fund",
                    // The web target, so Run can show it in the preview.
                    "npx --yes expo install react-dom react-native-web @expo/metro-runtime",
                )
            },
            guide = TemplateGuide(listOf("Expo (React Native), JavaScript", "react-native-web for the web target", "npm"),
                "npx expo start --localhost", null, "npm install", listOf(
                    "Run starts the web target (`expo start --web`) and shows it in the preview.",
                    "To open it in Expo Go on this phone: in a terminal run `npx expo start --localhost` (Metro on port 8081), then in Expo Go choose \"Enter URL manually\" and type exp://127.0.0.1:8081.",
                    "Expo Go only supports the newest Expo SDK. If it says the project is incompatible, run `npx expo install expo@latest --fix`.",
                    "Native builds (`expo run:android`, local EAS builds) don't work on the phone; use EAS Build in the cloud (`npx eas-cli build`).",
                )),
        ),

        ProjectTemplate(
            "flutter", "Flutter", "A Flutter app run as a web app inside the Ubuntu environment", TemplateGroup.Mobile, listOf(NODE, UBUNTU, FLUTTER),
            experimental = true,
            ignore = listOf(".dart_tool/", "build/", ".flutter-plugins", ".flutter-plugins-dependencies", ".packages", ".pub-cache/", ".DS_Store"),
            scaffold = { n ->
                listOf("yurlungr-ubuntu " + shellQuote("flutter create --platforms web,android --project-name ${n.dart} ."))
            },
            server = ServerRun("Flutter web (experimental)", "yurlungr-ubuntu \"flutter run -d web-server --web-hostname 127.0.0.1 --web-port \$PORT\"", startupTimeoutSeconds = 900),
            guide = TemplateGuide(listOf("Flutter and Dart, running inside the optional Ubuntu environment (proot)", "Platforms: web (works here), android (code only)"),
                "yurlungr-ubuntu \"flutter run -d web-server --web-hostname 127.0.0.1 --web-port \${PORT:-8080}\"",
                "yurlungr-ubuntu \"flutter test\"", "yurlungr-ubuntu \"flutter pub get\"", listOf(
                    "Experimental. There's no Flutter SDK for Android hosts, so every flutter/dart command runs through `yurlungr-ubuntu \"<command>\"`, which runs it inside Ubuntu in the current folder.",
                    "Only the web target runs here (Run opens it in the preview). Building an APK on the phone is unlikely to work; build Android releases on a computer or in CI.",
                    "Hot reload needs the interactive `flutter run` in a terminal (press r); Run restarts the whole server instead.",
                    "Everything is slower under proot: the first web compile can take several minutes.",
                )),
        ),
    )

    fun byId(id: String?): ProjectTemplate = all.firstOrNull { it.id == id } ?: all.first()

    private fun djangoFiles(n: TemplateNames): Map<String, String> = mapOf(
        "requirements.txt" to "Django>=5.2,<6\n",
        "manage.py" to "#!/usr/bin/env python\nimport os\nimport sys\n\n\ndef main():\n    os.environ.setdefault(\"DJANGO_SETTINGS_MODULE\", \"config.settings\")\n    from django.core.management import execute_from_command_line\n\n    execute_from_command_line(sys.argv)\n\n\nif __name__ == \"__main__\":\n    main()\n",
        "config/__init__.py" to "",
        "config/settings.py" to """from pathlib import Path
import os

BASE_DIR = Path(__file__).resolve().parent.parent

# Development settings. Set DJANGO_SECRET_KEY and DJANGO_DEBUG=0 before deploying.
SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "${n.secret}")
DEBUG = os.environ.get("DJANGO_DEBUG", "1") == "1"
ALLOWED_HOSTS = ["127.0.0.1", "localhost"]

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "config.wsgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": BASE_DIR / "db.sqlite3",
    }
}

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"
""",
        "config/views.py" to "from django.http import HttpResponse, JsonResponse\n\n\ndef index(request):\n    return HttpResponse(\"<h1>${n.folder}</h1><p>Django is running. Admin: <a href='/admin/'>/admin/</a></p>\")\n\n\ndef health(request):\n    return JsonResponse({\"ok\": True})\n",
        "config/urls.py" to "from django.contrib import admin\nfrom django.urls import path\n\nfrom . import views\n\nurlpatterns = [\n    path(\"\", views.index),\n    path(\"api/health\", views.health),\n    path(\"admin/\", admin.site.urls),\n]\n",
        "config/wsgi.py" to "import os\n\nfrom django.core.wsgi import get_wsgi_application\n\nos.environ.setdefault(\"DJANGO_SETTINGS_MODULE\", \"config.settings\")\n\napplication = get_wsgi_application()\n",
        "config/asgi.py" to "import os\n\nfrom django.core.asgi import get_asgi_application\n\nos.environ.setdefault(\"DJANGO_SETTINGS_MODULE\", \"config.settings\")\n\napplication = get_asgi_application()\n",
        "config/tests.py" to "from django.test import SimpleTestCase\n\n\nclass HealthTests(SimpleTestCase):\n    def test_health(self):\n        response = self.client.get(\"/api/health\")\n        self.assertEqual(response.status_code, 200)\n        self.assertEqual(response.json(), {\"ok\": True})\n",
    )
}
