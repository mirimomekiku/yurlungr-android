package dev.yurlungr.app.editor

/** What a run of characters is, for coloring. */
enum class TokenKind { Comment, Str, Number, Keyword, Literal, Type, Function, Annotation, Tag, Attribute, Property, Heading, Variable, Punctuation }

/** [start, end) in the text. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** How a language is lexed. C-like languages share one scanner; markup and markdown have their own. */
data class Language(
    val name: String,
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val quotes: String = "\"'",
    /** """…""" / '''…''' strings (Kotlin, Python, TOML). */
    val tripleQuotes: Boolean = false,
    val keywords: Set<String> = emptySet(),
    val literals: Set<String> = setOf("true", "false", "null"),
    /** `@Name` annotations / decorators. */
    val annotations: Boolean = false,
    /** `$var` / `${…}` variables (shell). */
    val dollarVars: Boolean = false,
    /** A quoted string directly followed by ':' is a key (JSON). */
    val keyedStrings: Boolean = false,
    /** `key:` / `key =` at line start is a property (YAML, TOML, properties). */
    val lineKeys: Boolean = false,
    val kind: Kind = Kind.Code,
    /** Prettier's parser can format it. */
    val prettier: Boolean = false,
) {
    enum class Kind { Code, Markup, Markdown, Plain }
}

object Languages {
    private fun words(s: String) = s.trim().split(Regex("\\s+")).toSet()

    private val C_LIKE_COMMENTS = listOf("//")
    private val BLOCK = "/*" to "*/"

    val Kotlin = Language(
        "Kotlin", C_LIKE_COMMENTS, BLOCK, "\"'", tripleQuotes = true, annotations = true,
        keywords = words(
            """package import class interface object fun val var if else when for while do return break continue
            throw try catch finally as is in out by get set init constructor companion data sealed enum open abstract
            override private protected public internal inline suspend operator infix lateinit const typealias this super
            where reified crossinline noinline vararg annotation value actual expect tailrec external""",
        ),
    )
    val Java = Language(
        "Java", C_LIKE_COMMENTS, BLOCK, annotations = true,
        keywords = words(
            """package import class interface enum extends implements new return if else for while do switch case default
            break continue throw throws try catch finally public private protected static final abstract synchronized
            volatile transient native void int long short byte char float double boolean this super instanceof var record
            sealed permits yield assert""",
        ),
    )
    private val jsKeywords = words(
        """var let const function return if else for while do switch case default break continue new delete typeof
        instanceof in of class extends super this import export from as async await yield try catch finally throw void
        static get set""",
    )
    val JavaScript = Language("JavaScript", C_LIKE_COMMENTS, BLOCK, "\"'`", keywords = jsKeywords, literals = setOf("true", "false", "null", "undefined", "NaN"), prettier = true)
    val TypeScript = JavaScript.copy(
        name = "TypeScript", annotations = true,
        keywords = jsKeywords + words("type interface enum implements private public protected readonly declare namespace abstract keyof infer is satisfies"),
    )
    val Json = Language("JSON", C_LIKE_COMMENTS, BLOCK, "\"", keyedStrings = true, prettier = true)
    val Css = Language("CSS", emptyList(), BLOCK, keywords = words("important media import supports keyframes font-face from to"), literals = emptySet(), prettier = true)
    val Scss = Css.copy(name = "SCSS", lineComments = C_LIKE_COMMENTS, dollarVars = true)
    val Python = Language(
        "Python", listOf("#"), null, tripleQuotes = true, annotations = true,
        keywords = words(
            """def class return if elif else for while in not and or is import from as with try except finally raise pass
            break continue lambda yield global nonlocal assert del async await match case""",
        ),
        literals = setOf("True", "False", "None"),
    )
    val Shell = Language(
        "Shell", listOf("#"), null, dollarVars = true,
        keywords = words("if then else elif fi for in do done while until case esac function return local export source set unset shift exit echo cd"),
        literals = emptySet(),
    )
    val Yaml = Language("YAML", listOf("#"), null, lineKeys = true, literals = setOf("true", "false", "null", "yes", "no", "on", "off"), prettier = true)
    val Toml = Language("TOML", listOf("#"), null, tripleQuotes = true, lineKeys = true, literals = setOf("true", "false"))
    val Properties = Language("Properties", listOf("#", "!"), null, quotes = "", lineKeys = true, literals = emptySet())
    val Groovy = Kotlin.copy(name = "Groovy", keywords = Kotlin.keywords + words("def task apply plugin dependencies repositories"))
    val Go = Language(
        "Go", C_LIKE_COMMENTS, BLOCK, "\"'`",
        keywords = words("package import func var const type struct interface map chan go defer select return if else for range switch case default break continue fallthrough goto"),
        literals = setOf("true", "false", "nil", "iota"),
    )
    val Rust = Language(
        "Rust", C_LIKE_COMMENTS, BLOCK,
        keywords = words("fn let mut const static struct enum trait impl pub use mod crate self Self super where match if else loop while for in return break continue as ref move async await unsafe dyn type"),
        literals = setOf("true", "false", "None", "Some", "Ok", "Err"),
    )
    val C = Language(
        "C/C++", C_LIKE_COMMENTS, BLOCK,
        keywords = words(
            """include define ifdef ifndef endif pragma int long short char float double void unsigned signed const static
            struct union enum typedef return if else for while do switch case default break continue sizeof class public
            private protected virtual namespace using template typename new delete auto nullptr""",
        ),
        literals = setOf("true", "false", "NULL", "nullptr"),
    )
    val Swift = Language(
        "Swift", C_LIKE_COMMENTS, BLOCK, annotations = true,
        keywords = words("import func let var class struct enum protocol extension return if else guard for in while switch case default break continue throw throws try catch self init deinit public private internal static override"),
        literals = setOf("true", "false", "nil"),
    )
    val Sql = Language(
        "SQL", listOf("--"), BLOCK, "'\"",
        keywords = words("select from where insert into values update set delete create table drop alter join left right inner outer on and or not null primary key index as order by group having limit distinct union"),
    )
    val Markup = Language("HTML", kind = Language.Kind.Markup, prettier = true)
    val Xml = Language("XML", kind = Language.Kind.Markup)
    val Markdown = Language("Markdown", kind = Language.Kind.Markdown, prettier = true)
    val Plain = Language("Plain text", kind = Language.Kind.Plain)

    /** By file name; Plain when unknown. */
    fun forFile(name: String): Language {
        val lower = name.lowercase()
        return when {
            lower == "dockerfile" || lower == "makefile" -> Shell
            lower.endsWith(".gradle.kts") -> Kotlin
            else -> when (lower.substringAfterLast('.', "")) {
                "kt", "kts" -> Kotlin
                "java" -> Java
                "js", "mjs", "cjs", "jsx" -> JavaScript
                "ts", "tsx", "mts", "cts" -> TypeScript
                "json", "jsonc", "json5", "webmanifest" -> Json
                "css", "less" -> Css
                "scss", "sass" -> Scss
                "html", "htm", "vue", "svelte" -> Markup
                "xml", "svg", "plist" -> Xml
                "md", "markdown", "mdx" -> Markdown
                "py", "pyw" -> Python
                "sh", "bash", "zsh", "fish", "env" -> Shell
                "yaml", "yml" -> Yaml
                "toml" -> Toml
                "properties", "ini", "cfg", "conf", "editorconfig" -> Properties
                "gradle", "groovy" -> Groovy
                "go" -> Go
                "rs" -> Rust
                "c", "h", "cpp", "cc", "hpp", "cxx", "m" -> C
                "swift" -> Swift
                "sql" -> Sql
                else -> if (lower == ".gitignore" || lower.startsWith(".env")) Shell else Plain
            }
        }
    }
}

/** Single-pass lexers. Linear in the text length; tolerant of unfinished input while typing. */
object Highlighter {
    fun tokenize(text: String, lang: Language): List<Token> = when (lang.kind) {
        Language.Kind.Code -> code(text, lang)
        Language.Kind.Markup -> markup(text)
        Language.Kind.Markdown -> markdown(text)
        Language.Kind.Plain -> emptyList()
    }

    private fun Char.isIdStart() = isLetter() || this == '_'
    private fun Char.isIdPart() = isLetterOrDigit() || this == '_'

    private fun code(s: String, lang: Language): List<Token> {
        val out = ArrayList<Token>()
        val n = s.length
        var i = 0
        var lineStart = true // only whitespace since the last newline
        while (i < n) {
            val c = s[i]
            if (c == '\n') { lineStart = true; i++; continue }
            if (c == ' ' || c == '\t') { i++; continue }

            // Line keys (YAML/TOML/properties): `key:` or `key =` at the start of a line.
            if (lang.lineKeys && lineStart && lang.lineComments.none { s.startsWith(it, i) }) {
                var j = i
                if (j < n && s[j] == '-') { j++; while (j < n && s[j] == ' ') j++ } // YAML list item
                val keyStart = j
                while (j < n && s[j] != '\n' && s[j] != ':' && s[j] != '=' && s[j] != '#') j++
                if (j < n && (s[j] == ':' || s[j] == '=') && j > keyStart && s.substring(keyStart, j).isNotBlank()) {
                    out += Token(keyStart, j, TokenKind.Property)
                    i = j + 1
                    lineStart = false
                    continue
                }
            }
            lineStart = false

            val line = lang.lineComments.firstOrNull { s.startsWith(it, i) }
            if (line != null) {
                val end = s.indexOf('\n', i).let { if (it < 0) n else it }
                out += Token(i, end, TokenKind.Comment)
                i = end
                continue
            }
            val block = lang.blockComment
            if (block != null && s.startsWith(block.first, i)) {
                val close = s.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                out += Token(i, end, TokenKind.Comment)
                i = end
                continue
            }
            if (c in lang.quotes) {
                val triple = lang.tripleQuotes && i + 2 < n && s[i + 1] == c && s[i + 2] == c
                val end = if (triple) {
                    val close = s.indexOf("$c$c$c", i + 3)
                    if (close < 0) n else close + 3
                } else {
                    var j = i + 1
                    while (j < n && s[j] != c && (s[j] != '\n' || c == '`')) {
                        if (s[j] == '\\') j++
                        j++
                    }
                    minOf(n, j + 1)
                }
                var k = end
                while (k < n && (s[k] == ' ' || s[k] == '\t')) k++
                val isKey = lang.keyedStrings && k < n && s[k] == ':'
                out += Token(i, end, if (isKey) TokenKind.Property else TokenKind.Str)
                i = end
                continue
            }
            if (c.isDigit() || (c == '.' && i + 1 < n && s[i + 1].isDigit())) {
                var j = i + 1
                while (j < n && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '_')) j++
                out += Token(i, j, TokenKind.Number)
                i = j
                continue
            }
            if (lang.annotations && c == '@' && i + 1 < n && s[i + 1].isIdStart()) {
                var j = i + 1
                while (j < n && (s[j].isIdPart() || s[j] == '.')) j++
                out += Token(i, j, TokenKind.Annotation)
                i = j
                continue
            }
            if (lang.dollarVars && c == '$' && i + 1 < n) {
                var j = i + 1
                if (s[j] == '{') {
                    val close = s.indexOf('}', j)
                    j = if (close < 0) n else close + 1
                } else {
                    while (j < n && (s[j].isIdPart() || (j == i + 1 && s[j] in "@#?*!0123456789"))) j++
                }
                if (j > i + 1) {
                    out += Token(i, j, TokenKind.Variable)
                    i = j
                    continue
                }
            }
            if (c.isIdStart() || (c == '-' && lang == Languages.Css)) {
                var j = i + 1
                while (j < n && (s[j].isIdPart() || (s[j] == '-' && (lang.name == "CSS" || lang.name == "SCSS")))) j++
                val word = s.substring(i, j)
                var k = j
                while (k < n && s[k] == ' ') k++
                val kind = when {
                    word in lang.keywords -> TokenKind.Keyword
                    word in lang.literals -> TokenKind.Literal
                    k < n && s[k] == '(' -> TokenKind.Function
                    (lang.name == "CSS" || lang.name == "SCSS") && k < n && s[k] == ':' -> TokenKind.Property
                    word[0].isUpperCase() && word.length > 1 -> TokenKind.Type
                    else -> null
                }
                if (kind != null) out += Token(i, j, kind)
                i = j
                continue
            }
            i++ // punctuation and operators stay in the base color; it reads calmer
        }
        return out
    }

    /** HTML/XML: comments, tags, attributes, attribute values. Text between tags stays plain. */
    private fun markup(s: String): List<Token> {
        val out = ArrayList<Token>()
        val n = s.length
        var i = 0
        while (i < n) {
            if (s.startsWith("<!--", i)) {
                val close = s.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                out += Token(i, end, TokenKind.Comment)
                i = end
                continue
            }
            if (s[i] == '<' && i + 1 < n && (s[i + 1].isLetter() || s[i + 1] == '/' || s[i + 1] == '?' || s[i + 1] == '!')) {
                var j = i + 1
                if (s[j] == '/' || s[j] == '?' || s[j] == '!') j++
                val nameStart = j
                while (j < n && (s[j].isLetterOrDigit() || s[j] in ":-_.")) j++
                out += Token(nameStart, j, TokenKind.Tag)
                // Attributes until '>'.
                while (j < n && s[j] != '>') {
                    val c = s[j]
                    when {
                        c == '"' || c == '\'' -> {
                            val close = s.indexOf(c, j + 1)
                            val end = if (close < 0) n else close + 1
                            out += Token(j, end, TokenKind.Str)
                            j = end
                        }
                        c.isLetter() || c == '_' || c == ':' || c == '@' -> {
                            val a = j
                            while (j < n && (s[j].isLetterOrDigit() || s[j] in ":-_.@")) j++
                            out += Token(a, j, TokenKind.Attribute)
                        }
                        else -> j++
                    }
                }
                i = minOf(n, j + 1)
                continue
            }
            if (s[i] == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    out += Token(i, semi + 1, TokenKind.Literal)
                    i = semi + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /** Markdown: headings, fenced code, inline code, emphasis markers, links, list bullets. */
    private fun markdown(s: String): List<Token> {
        val out = ArrayList<Token>()
        var offset = 0
        var inFence = false
        for (line in s.split('\n')) {
            val trimmed = line.trimStart()
            val lead = line.length - trimmed.length
            when {
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    out += Token(offset, offset + line.length, TokenKind.Comment)
                    inFence = !inFence
                }
                inFence -> out += Token(offset, offset + line.length, TokenKind.Str)
                trimmed.startsWith("#") -> out += Token(offset, offset + line.length, TokenKind.Heading)
                trimmed.startsWith(">") -> out += Token(offset, offset + line.length, TokenKind.Comment)
                else -> {
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") || Regex("^\\d+[.)] ").containsMatchIn(trimmed)) {
                        out += Token(offset + lead, offset + lead + trimmed.indexOf(' '), TokenKind.Keyword)
                    }
                    INLINE.findAll(line).forEach { m ->
                        val kind = when {
                            m.value.startsWith("`") -> TokenKind.Str
                            m.value.startsWith("[") || m.value.startsWith("!") -> TokenKind.Function
                            else -> TokenKind.Literal
                        }
                        out += Token(offset + m.range.first, offset + m.range.last + 1, kind)
                    }
                }
            }
            offset += line.length + 1
        }
        return out
    }

    private val INLINE = Regex("`[^`\n]+`|!?\\[[^\\]\n]*]\\([^)\n]*\\)|\\*\\*[^*\n]+\\*\\*|__[^_\n]+__")
}
