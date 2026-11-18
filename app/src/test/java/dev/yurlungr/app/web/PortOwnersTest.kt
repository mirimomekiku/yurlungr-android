package dev.yurlungr.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortOwnersTest {
    private val tcp = """
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 4567 1 0000000000000000 100 0 0 10 0
   1: 0100007F:C350 0100007F:1435 01 00000000:00000000 00:00000000 00000000 10123        0 4999 1 0000000000000000 20 4 30 10 -1
   2: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10050        0 777 1 0000000000000000 100 0 0 10 0
   garbage line
""".trimIndent()

    private val tcp6 = """
  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000001000000:1435 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 8888 1 0000000000000000 100 0 0 10 0
   1: 0000000000000000FFFF00000100007F:1F91 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10123        0 9999 1 0000000000000000 100 0 0 10 0
   2: 00000000000000000000000000000000:0016 00000000000000000000000000000000:0000 06 00000000:00000000 00:00000000 00000000 0        0 0 1 0000000000000000 100 0 0 10 0
""".trimIndent()

    @Test fun parsesIpv4Rows() {
        val rows = PortOwners.parseProcNet(tcp)
        assertEquals(3, rows.size)
        assertEquals(ProcSocket("127.0.0.1", 5173, 0x0A, 10123, 4567), rows[0])
        assertTrue(rows[0].listening)
        // An established connection from port 50000 to 5173 isn't a listener.
        assertEquals(50000, rows[1].port)
        assertEquals(1, rows[1].state)
        assertEquals("0.0.0.0", rows[2].address)
        assertEquals(8080, rows[2].port)
    }

    @Test fun parsesIpv6Rows() {
        val rows = PortOwners.parseProcNet(tcp6)
        assertEquals(3, rows.size)
        assertEquals("::1", rows[0].address)
        assertEquals("::ffff:127.0.0.1", rows[1].address)
        assertEquals(8081, rows[1].port)
        assertEquals("::", rows[2].address)
        assertEquals(6, rows[2].state)
    }

    @Test fun findsListenersOnAPortAcrossTables() {
        val found = PortOwners.listeners(listOf(tcp, tcp6), 5173)
        assertEquals(listOf(4567L, 8888L), found.map { it.inode })
        assertTrue(PortOwners.listeners(listOf(tcp, tcp6), 50000).isEmpty()) // only an established socket
        assertTrue(PortOwners.listeners(listOf(tcp, tcp6), 22).isEmpty()) // TIME_WAIT
    }

    @Test fun decodesAddresses() {
        assertEquals("127.0.0.1", PortOwners.decodeAddress("0100007F"))
        assertEquals("192.168.1.10", PortOwners.decodeAddress("0A01A8C0"))
        assertEquals("fe80::1", PortOwners.decodeAddress("000080FE000000000000000001000000"))
        // 2001:0db8:0000:0000:0000:ff00:0042:8329, each 32-bit word little-endian
        assertEquals("2001:db8::ff00:42:8329", PortOwners.decodeAddress("B80D0120" + "00000000" + "00FF0000" + "29834200"))
        assertNull(PortOwners.decodeAddress("XYZ0007F"))
        assertNull(PortOwners.decodeAddress("0100"))
    }

    @Test fun readsSocketLinksAndCommandLines() {
        assertEquals(4567L, PortOwners.socketInode("socket:[4567]"))
        assertNull(PortOwners.socketInode("/dev/null"))
        assertNull(PortOwners.socketInode("pipe:[4567]"))
        val raw = "node\u0000/data/x/node_modules/.bin/vite\u0000--port\u00005173\u0000".toByteArray()
        assertEquals("node /data/x/node_modules/.bin/vite --port 5173", PortOwners.commandLine(raw))
        assertEquals(10, PortOwners.commandLine(raw, max = 10).length)
    }
}
