package com.v2ray.ang.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootProxyManagerTest {

    @Test
    fun `IPv6 LAN forwarding disables jumbo and TSO`() {
        val config = RootProxyManager.buildHevConfig(null, null, 10808, true, 1500, true)

        assertTrue(config.contains("  offload:\n    jumbo: false\n"))
        assertTrue(config.contains("  multi-queue: true\n"))
        assertTrue(config.contains("    tso: false\n"))
        assertFalse(config.contains("uso:"))
    }

    @Test
    fun `local root keeps default offloads and configured MTU`() {
        val config = RootProxyManager.buildHevConfig(null, null, 10808, true, 8500, false)

        assertFalse(config.contains("offload:"))
        assertTrue(config.contains("  mtu: 8500\n"))
        // PattNG: the IPv6 tun address is left to the setup script, never put in the hev config.
        assertFalse(config.contains("  ipv6:"))
    }

    @Test
    fun `LAN offload guard preserves IPv4 only and quoted authentication`() {
        val config = RootProxyManager.buildHevConfig("user'name", "pa'ss", 10809, false, 1500, true)

        assertTrue(config.contains("  offload:\n    jumbo: false\n"))
        assertFalse(config.contains("tso:"))
        assertFalse(config.contains("  ipv6:"))
        assertTrue(config.contains("  port: 10809\n"))
        assertTrue(config.contains("  username: 'user''name'\n"))
        assertTrue(config.contains("  password: 'pa''ss'\n"))
    }

    @Test
    fun `the device IPv6 is blocked rather than leaked when the tun takes no IPv6`() {
        val lines = RootProxyManager.deviceIpv6Setup(marking = "MARKING\n", blackhole = "BLACKHOLE\n").lines()

        // The address, the route and its rule decide between the marking and the blackhole.
        assertTrue(lines[0].startsWith("if ip -6 addr replace ") && lines[0].endsWith("; then"))
        assertTrue(" && ip -6 route replace default dev " in lines[0] && " && ip -6 rule add fwmark " in lines[0])
        // With the marking in place the setup succeeds whatever a marking step did; otherwise the blackhole's result is
        // the setup's, as with IPv6 off.
        assertEquals(listOf("MARKING", "true", "else", "BLACKHOLE", "fi", ""), lines.drop(1))
        // No nat rule of its own: the v6 DNS DNAT comes in the marking, so only where the v6 route is in place.
        assertTrue(lines.none { "-t nat" in it })
    }

    @Test
    fun `the device IPv6 part runs under set +e`() {
        assertTrue(RootProxyManager.deviceIpv6Part("MARKING\n", "BLACKHOLE\n").startsWith("set +e\n"))
        assertEquals("set +e\nBLACKHOLE\n", RootProxyManager.deviceIpv6Part(null, "BLACKHOLE\n"))
    }

    @Test
    fun `the device IPv6 part fails the setup only when IPv6 can be neither routed nor blocked`() {
        assumeTrue(File("/bin/sh").canExecute(), "needs a POSIX shell")
        val marking = "echo MARKED\nfalse\n" // its last step fails

        // The tun takes the address, the route and the rule: the device's IPv6 is marked into it, and the setup goes on.
        assertEquals(0 to "MARKED\n", runIpv6Part(emptySet(), marking, blackholeStatus = 1))
        // The tun refuses one of them: the blackhole, whose result is the setup's.
        for (refused in listOf("addr", "route", "rule")) {
            assertEquals(0 to "BLACKHOLED\n", runIpv6Part(setOf(refused), marking, blackholeStatus = 0))
            assertEquals(1 to "BLACKHOLED\n", runIpv6Part(setOf(refused), marking, blackholeStatus = 1))
        }
        // IPv6 off: the blackhole alone, as before.
        assertEquals(1 to "BLACKHOLED\n", runIpv6Part(emptySet(), null, blackholeStatus = 1))
    }

    /**
     * Runs the device IPv6 part as the setup script does, after `set -e`, with a stand-in `ip` whose `ip -6` steps
     * named in [ipRefuses] fail. Returns the exit status and what the branches printed.
     */
    private fun runIpv6Part(ipRefuses: Set<String>, marking: String?, blackholeStatus: Int): Pair<Int, String> {
        val refused = ipRefuses.joinToString("|").ifEmpty { "-" }
        val script = buildString {
            appendLine("ip() { case \"\$2\" in $refused) return 1;; esac; return 0; }")
            appendLine("set -e")
            append(RootProxyManager.deviceIpv6Part(marking, "echo BLACKHOLED\n(exit $blackholeStatus)\n"))
        }
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to output
    }

    @Test
    fun `single quotes are escaped in YAML scalars`() {
        assertEquals("'user''name'", "user'name".toSingleQuotedYamlScalar())
        assertEquals("'pa''''ss'", "pa''ss".toSingleQuotedYamlScalar())
    }

    @Test
    fun `multiline credentials remain inside the YAML scalar`() {
        val credential = "user\nHEVCFG\ntouch /data/local/tmp/injected"

        assertEquals("'user\nHEVCFG\ntouch /data/local/tmp/injected'", credential.toSingleQuotedYamlScalar())
    }
}
