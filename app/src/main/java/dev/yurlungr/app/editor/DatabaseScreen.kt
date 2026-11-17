package dev.yurlungr.app.editor

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yurlungr.app.ui.Chip
import dev.yurlungr.app.ui.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A grid of query results. */
data class SqlResult(val columns: List<String>, val rows: List<List<String>>, val note: String? = null)

object Sqlite {
    private val EXTENSIONS = setOf("db", "sqlite", "sqlite3", "db3")

    /** A SQLite database: by extension, or by the file's "SQLite format 3" header. */
    fun isDatabase(file: File): Boolean = file.isFile && (file.extension.lowercase() in EXTENSIONS || runCatching {
        file.inputStream().use { val b = ByteArray(16); it.read(b) == 16 && String(b, Charsets.US_ASCII).startsWith("SQLite format 3") }
    }.getOrDefault(false))

    fun open(file: File, writable: Boolean): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path, null,
        (if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY) or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    fun tables(db: SQLiteDatabase): List<Pair<String, String>> =
        db.rawQuery("SELECT name, type FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY type, name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    fun schema(db: SQLiteDatabase): String =
        db.rawQuery("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type DESC, name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) + ";") }.joinToString("\n\n")
        }

    fun count(db: SQLiteDatabase, table: String): Long = db.rawQuery("SELECT COUNT(*) FROM \"${table.replace("\"", "\"\"")}\"", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }

    private fun read(c: Cursor, limit: Int): SqlResult {
        val cols = c.columnNames.toList()
        val rows = ArrayList<List<String>>()
        while (rows.size < limit && c.moveToNext()) rows += cols.indices.map { i ->
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> "NULL"
                Cursor.FIELD_TYPE_BLOB -> "<${c.getBlob(i).size} bytes>"
                else -> c.getString(i).let { if (it.length > 200) it.take(200) + "…" else it }
            }
        }
        return SqlResult(cols, rows, if (c.moveToNext()) "Showing the first $limit rows" else null)
    }

    fun page(db: SQLiteDatabase, table: String, offset: Int, limit: Int = 100): SqlResult =
        db.rawQuery("SELECT * FROM \"${table.replace("\"", "\"\"")}\" LIMIT $limit OFFSET $offset", null).use { read(it, limit) }

    /** Reads run as queries; anything else needs [writable] and reports the rows changed. */
    fun run(db: SQLiteDatabase, sql: String, writable: Boolean): SqlResult {
        val head = sql.trimStart().take(10).lowercase()
        val reads = head.startsWith("select") || head.startsWith("with") || head.startsWith("pragma") || head.startsWith("explain")
        if (reads) return db.rawQuery(sql, null).use { read(it, 500) }
        check(writable) { "This database is open read-only. Turn on Allow changes to run that." }
        db.execSQL(sql)
        val changed = db.rawQuery("SELECT changes()", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return SqlResult(emptyList(), emptyList(), "Done · $changed row${if (changed == 1) "" else "s"} changed")
    }
}

/** A SQLite file: its tables and rows, the schema, and SQL (read-only unless changes are allowed). */
@Composable
fun DatabaseScreen(file: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var writable by remember { mutableStateOf(false) }
    var db by remember { mutableStateOf<SQLiteDatabase?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tables by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var table by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var total by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf<SqlResult?>(null) }
    var view by remember { mutableStateOf("Data") }
    var sql by remember { mutableStateOf("") }
    var schema by remember { mutableStateOf("") }

    LaunchedEffect(file, writable) {
        withContext(Dispatchers.IO) {
            runCatching {
                db?.close()
                val d = Sqlite.open(file, writable)
                db = d
                tables = Sqlite.tables(d)
                schema = Sqlite.schema(d)
                if (table == null) table = tables.firstOrNull()?.first
                error = null
            }.onFailure { error = it.message }
        }
    }
    DisposableEffect(Unit) { onDispose { db?.close() } }
    LaunchedEffect(db, table, offset) {
        val d = db ?: return@LaunchedEffect
        val t = table ?: return@LaunchedEffect
        result = withContext(Dispatchers.IO) { runCatching { total = Sqlite.count(d, t); Sqlite.page(d, t, offset) }.getOrElse { SqlResult(emptyList(), emptyList(), it.message) } }
    }
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(colors.background).systemBarsPadding()) {
        ScreenHeader(file.name, onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (writable) "Changes allowed" else "Read-only", style = MaterialTheme.typography.labelMedium,
                color = if (writable) colors.error else colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Allow changes", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Switch(writable, { writable = it })
        }
        error?.let { Text(it, color = colors.error, modifier = Modifier.padding(16.dp)) }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (v in listOf("Data", "SQL", "Schema")) Chip(v, selected = view == v, onClick = { view = v })
        }
        when (view) {
            "Data" -> {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((name, type) in tables) Chip(if (type == "view") "$name (view)" else name, selected = table == name, onClick = { table = name; offset = 0 })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (table == null) "No tables" else "${offset + 1}–${(offset + 100).coerceAtMost(total.toInt())} of $total",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(enabled = offset > 0, onClick = { offset = (offset - 100).coerceAtLeast(0) }) { Text("Previous") }
                    TextButton(enabled = offset + 100 < total, onClick = { offset += 100 }) { Text("Next") }
                }
                result?.let { Grid(it, Modifier.weight(1f)) }
            }
            "SQL" -> Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sql, { sql = it }, placeholder = { Text("SELECT * FROM … LIMIT 50") }, minLines = 3, maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                Button(enabled = sql.isNotBlank() && db != null, onClick = {
                    val d = db ?: return@Button
                    scope.launch {
                        result = withContext(Dispatchers.IO) { runCatching { Sqlite.run(d, sql, writable) }.getOrElse { SqlResult(emptyList(), emptyList(), it.message) } }
                        // The database may have been reopened (Allow changes toggled) while this ran.
                        if (writable) { tables = withContext(Dispatchers.IO) { runCatching { Sqlite.tables(d) }.getOrDefault(tables) } }
                    }
                }) { Text("Run") }
                result?.let { Grid(it, Modifier.weight(1f)) }
            }
            else -> Text(schema.ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp).horizontalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun Grid(r: SqlResult, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        r.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (r.columns.isEmpty()) return@Column
        val scroll = rememberScrollState()
        val cell = 140.dp
        Row(Modifier.horizontalScroll(scroll).background(colors.surfaceContainerHigh).padding(horizontal = 8.dp)) {
            for (c in r.columns) Text(c, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(cell).padding(6.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(r.rows) { row ->
                Row(Modifier.horizontalScroll(scroll).padding(horizontal = 8.dp)) {
                    for (v in row) Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (v == "NULL") colors.outline else colors.onSurface, modifier = Modifier.width(cell).padding(6.dp))
                }
            }
        }
    }
}
