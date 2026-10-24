package dev.netpilot.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutesTest {
    private fun covered(routes: List<Cidr4>, ip: String) = Cidr4.parse("$ip/32").address.let { a -> routes.any { a in it.address..it.end } }

    @Test fun coversPublicButNotLocal() {
        val r = Routes.publicV4()
        for (ip in listOf("1.1.1.1", "8.8.8.8", "100.64.0.1", "172.15.255.255", "172.32.0.0", "192.167.255.255", "223.255.255.255", "11.0.0.0"))
            assertTrue(covered(r, ip), "$ip should go through the VPN")
        for (ip in listOf("10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255", "169.254.1.1", "224.0.0.251", "239.255.255.250", "255.255.255.255", "127.0.0.1", "0.0.0.0"))
            assertFalse(covered(r, ip), "$ip should stay local")
    }

    @Test fun routesDontOverlapAndAreCompact() {
        val r = Routes.publicV4().sortedBy { it.address }
        r.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.address) }
        val total = r.sumOf { it.size }
        val excluded = Routes.LOCAL_V4.sumOf { it.size }
        assertEquals((1L shl 32) - excluded, total)
        assertEquals(46, r.size) // the minimum: excluded blocks split the space at many alignment boundaries
        assertEquals("1.0.0.0/8", r.first().toString())
    }
}
