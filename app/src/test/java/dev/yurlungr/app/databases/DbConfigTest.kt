package dev.yurlungr.app.databases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DbConfigTest {
    @Test fun `project ids are stable, readable and shell-safe`() {
        val a = DbNaming.projectId("/data/data/com.termux/files/home/projects/My App!")
        assertEquals(a, DbNaming.projectId("/data/data/com.termux/files/home/projects/My App!"))
        assertTrue(a, a.startsWith("my-app-"))
        assertTrue(a, Regex("^[a-z0-9-]+$").matches(a))
        // Same name, different folder: different data.
        assertNotEquals(a, DbNaming.projectId("/data/data/com.termux/files/home/work/My App!"))
        assertTrue(DbNaming.projectId("/x/ÄÖÜ").startsWith("project-"))
        assertTrue(DbNaming.projectId("/x/" + "a".repeat(80)).length <= 24 + 1 + 10)
    }

    @Test fun `database names are SQL identifiers`() {
        assertEquals("my_app", DbNaming.databaseName("My-App"))
        assertEquals("db_2048", DbNaming.databaseName("2048"))
        assertEquals("app", DbNaming.databaseName("---"))
        for (n in listOf("My-App", "2048", "---", "a b.c", "x".repeat(100))) assertTrue(n, DbNaming.isValidDatabaseName(DbNaming.databaseName(n)))
    }

    @Test fun `passwords are long and safe to embed`() {
        val p = DbNaming.newPassword(Random(1))
        assertEquals(24, p.length)
        assertTrue(DbNaming.isValidPassword(p))
        assertFalse(DbNaming.isValidPassword("short"))
        assertFalse(DbNaming.isValidPassword("has'quote-and-more-chars"))
        assertNotEquals(p, DbNaming.newPassword(Random(2)))
    }

    @Test fun `ports skip other projects and busy ones`() {
        assertEquals(5432, DbNaming.pickPort(5432, emptySet(), { false }))
        assertEquals(5434, DbNaming.pickPort(5432, setOf(5432), { it == 5433 }))
        assertNull(DbNaming.pickPort(5432, emptySet(), { true }, span = 5))
    }

    @Test fun `pid files are trusted only for the right program`() {
        assertEquals(1234, DbNaming.parsePid("1234\n/data/x/postgres\n1700000000\n5433\n"))
        assertNull(DbNaming.parsePid("garbage"))
        assertNull(DbNaming.parsePid("1"))
        assertTrue(DbNaming.cmdlineMatches("/data/data/com.termux/files/usr/bin/postgres\u0000-D\u0000/x\u0000", DbKind.Postgres.processNames))
        assertTrue(DbNaming.cmdlineMatches("redis-server 127.0.0.1:6380\u0000\u0000\u0000", DbKind.Redis.processNames))
        assertTrue(DbNaming.cmdlineMatches("/usr/bin/mysqld\u0000--no-defaults\u0000", DbKind.MariaDb.processNames))
        // A reused pid: some other program now.
        assertFalse(DbNaming.cmdlineMatches("/system/bin/sh\u0000-c\u0000postgres\u0000", DbKind.Postgres.processNames))
        assertFalse(DbNaming.cmdlineMatches("node\u0000postgres-client.js\u0000", DbKind.Postgres.processNames))
        assertFalse(DbNaming.cmdlineMatches("", DbKind.Redis.processNames))
    }

    @Test fun `config round-trips and rejects damage`() {
        val c = DbProjectConfig("/h/projects/a b", "Abcdefghijklmnop1234", mapOf(DbKind.Postgres to 5433, DbKind.Redis to 6380))
        assertEquals(c, DbProjectConfig.parse(c.format()))
        assertNull(DbProjectConfig.parse("project=/x\npassword=bad'pw\n"))
        assertNull(DbProjectConfig.parse("password=Abcdefghijklmnop1234\n"))
        val noPorts = DbProjectConfig.parse("project=/x\npassword=Abcdefghijklmnop1234\npostgres.port=80\nredis.port=x\n")!!
        assertTrue(noPorts.ports.isEmpty())
    }

    @Test fun `connection strings and their masked form`() {
        assertEquals("postgresql://postgres:pw@127.0.0.1:5433/app", DbKind.Postgres.url(5433, "pw", "app"))
        assertEquals("redis://:pw@127.0.0.1:6380", DbKind.Redis.url(6380, "pw", "app"))
        assertEquals("mysql://root:pw@127.0.0.1:3307/app", DbKind.MariaDb.url(3307, "pw", "app"))
        assertFalse(DbKind.Postgres.masked(5433, "app").contains("pw"))
        assertEquals("REDIS_URL", DbKind.Redis.envKey)
    }

    @Test fun `the server script validates its arguments before using them`() {
        val text = java.io.File("src/main/assets/project-db.sh").readText()
        for (guard in listOf("*[!A-Za-z0-9/._-]*", "*[!a-z0-9_]*", "''|*[!A-Za-z0-9]*", "--auth-host=scram-sha-256", "listen_addresses=127.0.0.1", "bind 127.0.0.1", "requirepass", "--skip-networking")) {
            assertTrue(guard, text.contains(guard))
        }
        // The password never goes on a command line.
        assertFalse(text.contains("-p\$PW") || text.contains("-a \"\$PW\""))
    }
}
