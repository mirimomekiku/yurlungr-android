package dev.yurlungr.app.databases

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

/** A database server a project can run. [id] is what `project-db.sh` takes. */
enum class DbKind(
    val id: String,
    val title: String,
    val basePort: Int,
    /** The variable "Add to .env" writes. */
    val envKey: String,
    /** Names the server's process can have (first word of its command line); its pid file is checked against them. */
    val processNames: List<String>,
    /** Programs (in `$PREFIX/bin`) it needs; any one of each group will do. */
    val programs: List<List<String>>,
) {
    Postgres("postgres", "PostgreSQL", 5432, "DATABASE_URL", listOf("postgres"),
        listOf(listOf("initdb"), listOf("pg_ctl"), listOf("psql"))),
    Redis("redis", "Redis", 6379, "REDIS_URL", listOf("redis-server"),
        listOf(listOf("redis-server"))),
    MariaDb("mariadb", "MariaDB", 3306, "DATABASE_URL", listOf("mariadbd", "mysqld"),
        listOf(listOf("mariadbd", "mysqld"), listOf("mariadb-install-db", "mysql_install_db"), listOf("mariadb", "mysql"))),
    ;

    /** Its data folder inside the project's folder. */
    val folder: String get() = id

    /** The pid file `project-db.sh` asks the server to write, relative to the project's folder. */
    val pidFile: String get() = when (this) {
        Postgres -> "postgres/postmaster.pid"
        Redis -> "redis/redis.pid"
        MariaDb -> "mariadb/mariadb.pid"
    }

    val logFile: String get() = "$id.log"

    /** How apps connect: a URL with the password in it (show [masked] instead). */
    fun url(port: Int, password: String, database: String): String = when (this) {
        Postgres -> "postgresql://postgres:$password@127.0.0.1:$port/$database"
        Redis -> "redis://:$password@127.0.0.1:$port"
        MariaDb -> "mysql://root:$password@127.0.0.1:$port/$database"
    }

    fun masked(port: Int, database: String): String = url(port, "••••", database)
}

/** One project's database settings, kept in `db.properties` in its data folder (outside the repo). */
data class DbProjectConfig(
    val projectPath: String,
    val password: String,
    val ports: Map<DbKind, Int> = emptyMap(),
) {
    fun port(kind: DbKind): Int? = ports[kind]

    fun withPort(kind: DbKind, port: Int) = copy(ports = ports + (kind to port))

    fun format(): String {
        val p = Properties()
        p["project"] = projectPath
        p["password"] = password
        for ((kind, port) in ports) p["${kind.id}.port"] = port.toString()
        return java.io.StringWriter().also { p.store(it, "Yurlungr project databases. Don't commit the password.") }.toString()
    }

    companion object {
        fun parse(text: String): DbProjectConfig? {
            val p = Properties()
            runCatching { p.load(text.reader()) }.getOrElse { return null }
            val project = p.getProperty("project")?.takeIf { it.isNotBlank() } ?: return null
            val password = p.getProperty("password")?.takeIf { DbNaming.isValidPassword(it) } ?: return null
            val ports = DbKind.entries.mapNotNull { kind ->
                p.getProperty("${kind.id}.port")?.trim()?.toIntOrNull()?.takeIf(DbNaming::isUsablePort)?.let { kind to it }
            }.toMap()
            return DbProjectConfig(project, password, ports)
        }
    }
}

/** Names, ports and passwords for project databases: pure, so they can be tested off the phone. */
object DbNaming {
    private val PASSWORD_CHARS = ('a'..'z') + ('A'..'Z') + ('0'..'9')

    /**
     * A stable folder name for a project: a readable slug of its name plus a hash of its full
     * path, so two projects with the same name never share data. Only `[a-z0-9-]`: the scripts
     * pass it through `pg_ctl -o`, which a shell reads.
     */
    fun projectId(canonicalPath: String): String {
        val name = canonicalPath.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val slug = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(24).ifEmpty { "project" }
        return "$slug-${hash(canonicalPath).take(10)}"
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The database created for the project: its name as a SQL identifier (`[a-z0-9_]`, starting with a letter). */
    fun databaseName(projectName: String): String {
        val s = projectName.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_').take(48)
        return when {
            s.isEmpty() -> "app"
            s.first().isDigit() -> "db_$s"
            else -> s
        }
    }

    fun isValidDatabaseName(name: String) = Regex("^[a-z][a-z0-9_]{0,62}$").matches(name)

    fun newPassword(random: java.util.Random = SecureRandom()): String =
        (1..24).map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.size)] }.joinToString("")

    /** Letters and digits only: it goes into URLs, SQL and config files without escaping. */
    fun isValidPassword(p: String) = p.length in 16..64 && p.all { it in PASSWORD_CHARS }

    fun isUsablePort(port: Int) = port in 1024..65535

    /**
     * The first port from [base] up that no other project has claimed ([reserved]) and nothing is
     * listening on ([inUse]); null when the next [span] ports are all taken.
     */
    fun pickPort(base: Int, reserved: Set<Int>, inUse: (Int) -> Boolean, span: Int = 200): Int? =
        (base until base + span).firstOrNull { isUsablePort(it) && it !in reserved && !inUse(it) }

    /** The pid on the first line of a pid file (PostgreSQL's postmaster.pid has more lines). */
    fun parsePid(text: String): Int? = text.lineSequence().firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 1 }

    /**
     * Whether `/proc/<pid>/cmdline` ([cmdline], NUL-separated) is one of [names]: a pid file left
     * behind after a crash may name a pid that now belongs to something else, which must never be
     * signalled. Redis rewrites its title to "redis-server 127.0.0.1:6379", so only the first word counts.
     */
    fun cmdlineMatches(cmdline: String, names: List<String>): Boolean {
        val first = cmdline.split('\u0000').firstOrNull().orEmpty().trim().substringBefore(' ')
        val base = first.substringAfterLast('/')
        return base.isNotEmpty() && names.any { base == it }
    }
}
