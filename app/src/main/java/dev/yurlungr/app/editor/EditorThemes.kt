package dev.yurlungr.app.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Editor colors: surfaces plus one color per token kind. Property names match what the editor draws. */
class EditorTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val Bg: Color,
    val Bar: Color,
    val Fg: Color,
    /** Gutter numbers, comments, hints. */
    val Dim: Color,
    val CurrentLine: Color,
    val Selection: Color,
    val Match: Color,
    val MatchCurrent: Color,
    /** Cursor, handles, primary actions. */
    val Accent: Color,
    val Modified: Color,
    private val comment: Color,
    private val string: Color,
    private val number: Color,
    private val keyword: Color,
    private val type: Color,
    private val function: Color,
    private val property: Color,
    private val variable: Color,
    private val tag: Color = keyword,
    private val attribute: Color = function,
    private val italicTypes: Boolean = false,
    private val italicComments: Boolean = true,
) {
    fun style(kind: TokenKind): SpanStyle = when (kind) {
        TokenKind.Comment -> SpanStyle(color = comment, fontStyle = if (italicComments) FontStyle.Italic else null)
        TokenKind.Str -> SpanStyle(color = string)
        TokenKind.Number, TokenKind.Literal -> SpanStyle(color = number)
        TokenKind.Keyword -> SpanStyle(color = keyword)
        TokenKind.Tag -> SpanStyle(color = tag)
        TokenKind.Type -> SpanStyle(color = type, fontStyle = if (italicTypes) FontStyle.Italic else null)
        TokenKind.Function, TokenKind.Annotation -> SpanStyle(color = function)
        TokenKind.Attribute -> SpanStyle(color = attribute)
        TokenKind.Property -> SpanStyle(color = property)
        TokenKind.Heading -> SpanStyle(color = keyword, fontWeight = FontWeight.Bold)
        TokenKind.Variable -> SpanStyle(color = variable)
        TokenKind.Punctuation -> SpanStyle(color = Fg)
    }
}

object EditorThemes {
    val Monokai = EditorTheme(
        "monokai", "Monokai", true,
        Bg = Color(0xFF1E1F1C), Bar = Color(0xFF181916), Fg = Color(0xFFF8F8F2), Dim = Color(0xFF75715E),
        CurrentLine = Color(0xFF2A2B26), Selection = Color(0x6649483E), Match = Color(0x55E6DB74), MatchCurrent = Color(0xAAFD971F),
        Accent = Color(0xFFA6E22E), Modified = Color(0xFFFD971F),
        comment = Color(0xFF75715E), string = Color(0xFFE6DB74), number = Color(0xFFAE81FF), keyword = Color(0xFFF92672),
        type = Color(0xFF66D9EF), function = Color(0xFFA6E22E), property = Color(0xFF66D9EF), variable = Color(0xFFFD971F),
        italicTypes = true,
    )
    val Mariana = EditorTheme(
        "mariana", "Mariana", true,
        Bg = Color(0xFF303841), Bar = Color(0xFF29303A), Fg = Color(0xFFD8DEE9), Dim = Color(0xFFA6ACB9),
        CurrentLine = Color(0xFF3A434E), Selection = Color(0x804F5B66), Match = Color(0x55FAC863), MatchCurrent = Color(0xAAF99157),
        Accent = Color(0xFF6699CC), Modified = Color(0xFFF99157),
        comment = Color(0xFFA6ACB9), string = Color(0xFF99C794), number = Color(0xFFF99157), keyword = Color(0xFFC594C5),
        type = Color(0xFFFAC863), function = Color(0xFF6699CC), property = Color(0xFF5FB4B4), variable = Color(0xFFEC5F66),
        tag = Color(0xFFEC5F66), attribute = Color(0xFFC594C5),
    )
    val Dracula = EditorTheme(
        "dracula", "Dracula", true,
        Bg = Color(0xFF282A36), Bar = Color(0xFF21222C), Fg = Color(0xFFF8F8F2), Dim = Color(0xFF6272A4),
        CurrentLine = Color(0xFF343746), Selection = Color(0x8044475A), Match = Color(0x55F1FA8C), MatchCurrent = Color(0xAAFFB86C),
        Accent = Color(0xFFBD93F9), Modified = Color(0xFFFFB86C),
        comment = Color(0xFF6272A4), string = Color(0xFFF1FA8C), number = Color(0xFFBD93F9), keyword = Color(0xFFFF79C6),
        type = Color(0xFF8BE9FD), function = Color(0xFF50FA7B), property = Color(0xFF8BE9FD), variable = Color(0xFFFFB86C),
        italicTypes = true,
    )
    val OneDark = EditorTheme(
        "one-dark", "One Dark", true,
        Bg = Color(0xFF282C34), Bar = Color(0xFF21252B), Fg = Color(0xFFABB2BF), Dim = Color(0xFF5C6370),
        CurrentLine = Color(0xFF2C313C), Selection = Color(0x803E4451), Match = Color(0x55E5C07B), MatchCurrent = Color(0xAAD19A66),
        Accent = Color(0xFF528BFF), Modified = Color(0xFFD19A66),
        comment = Color(0xFF5C6370), string = Color(0xFF98C379), number = Color(0xFFD19A66), keyword = Color(0xFFC678DD),
        type = Color(0xFFE5C07B), function = Color(0xFF61AFEF), property = Color(0xFFE06C75), variable = Color(0xFFE06C75),
        tag = Color(0xFFE06C75), attribute = Color(0xFFD19A66),
    )
    val GitHubDark = EditorTheme(
        "github-dark", "GitHub Dark", true,
        Bg = Color(0xFF0D1117), Bar = Color(0xFF010409), Fg = Color(0xFFE6EDF3), Dim = Color(0xFF6E7681),
        CurrentLine = Color(0xFF161B22), Selection = Color(0x66388BFD), Match = Color(0x55BB800A), MatchCurrent = Color(0xAAD29922),
        Accent = Color(0xFF58A6FF), Modified = Color(0xFFD29922),
        comment = Color(0xFF8B949E), string = Color(0xFFA5D6FF), number = Color(0xFF79C0FF), keyword = Color(0xFFFF7B72),
        type = Color(0xFFFFA657), function = Color(0xFFD2A8FF), property = Color(0xFF79C0FF), variable = Color(0xFFFFA657),
        tag = Color(0xFF7EE787), attribute = Color(0xFF79C0FF), italicComments = false,
    )
    val GitHubLight = EditorTheme(
        "github-light", "GitHub Light", false,
        Bg = Color(0xFFFFFFFF), Bar = Color(0xFFF6F8FA), Fg = Color(0xFF1F2328), Dim = Color(0xFF8C959F),
        CurrentLine = Color(0xFFF6F8FA), Selection = Color(0x5554AEFF), Match = Color(0x66FFDF5D), MatchCurrent = Color(0xAAFFA657),
        Accent = Color(0xFF0969DA), Modified = Color(0xFFBC4C00),
        comment = Color(0xFF6E7781), string = Color(0xFF0A3069), number = Color(0xFF0550AE), keyword = Color(0xFFCF222E),
        type = Color(0xFF953800), function = Color(0xFF8250DF), property = Color(0xFF0550AE), variable = Color(0xFF953800),
        tag = Color(0xFF116329), attribute = Color(0xFF0550AE), italicComments = false,
    )
    val SolarizedLight = EditorTheme(
        "solarized-light", "Solarized Light", false,
        Bg = Color(0xFFFDF6E3), Bar = Color(0xFFEEE8D5), Fg = Color(0xFF586E75), Dim = Color(0xFF93A1A1),
        CurrentLine = Color(0xFFEEE8D5), Selection = Color(0x66B5C9C4), Match = Color(0x66B58900), MatchCurrent = Color(0xAACB4B16),
        Accent = Color(0xFF268BD2), Modified = Color(0xFFCB4B16),
        comment = Color(0xFF93A1A1), string = Color(0xFF2AA198), number = Color(0xFFD33682), keyword = Color(0xFF859900),
        type = Color(0xFFB58900), function = Color(0xFF268BD2), property = Color(0xFF268BD2), variable = Color(0xFFCB4B16),
        tag = Color(0xFF268BD2), attribute = Color(0xFF93A1A1),
    )

    val all = listOf(Monokai, Mariana, Dracula, OneDark, GitHubDark, GitHubLight, SolarizedLight)

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: Monokai
}

/** Editor preferences, observable from Compose; saved in SharedPreferences. */
object EditorPrefs {
    private const val PREFS = "editor"
    var theme by mutableStateOf(EditorThemes.Monokai)
        private set
    var fontSize by mutableFloatStateOf(13f)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = EditorThemes.byId(p.getString("theme", null))
        fontSize = p.getFloat("font", 13f)
    }

    fun setTheme(context: Context, t: EditorTheme) {
        theme = t
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", t.id).apply()
    }

    fun setFontSize(context: Context, size: Float) {
        fontSize = size.coerceIn(9f, 22f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("font", fontSize).apply()
    }
}
