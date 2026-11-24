package com.v2ray.ang

import com.v2ray.ang.util.Utils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class UtilsTest {

    @Test
    fun test_parseInt() {
        assertEquals(Utils.parseInt("1234"), 1234)
    }

    @Test
    fun theDefaultRemoteDnsIsGooglesDnsOverHttpsByAddress() {
        assertEquals("https://8.8.8.8/dns-query", AppConfig.DNS_PROXY)
        // Taken by the Remote DNS setting's filter, see SettingsManager.getRemoteDnsServers, and reached without a
        // name to look up first.
        assertTrue(Utils.isCoreDNSAddress(AppConfig.DNS_PROXY))
        assertTrue(Utils.isPureIpAddress(URI(AppConfig.DNS_PROXY).host))
    }

    @Test
    fun ipv6IsOnByDefault() {
        // PattNG: "Enable IPv6" reads as this while no value is stored.
        assertTrue(AppConfig.DEFAULT_IPV6_ENABLED)
    }

    @Test
    fun everyReadOfEnableIpv6TakesItsDefault() {
        // PattNG: so that "Enable IPv6" reads as on everywhere while no value is stored. Upstream's default is off, and
        // its code reads the setting without one: the MASQUE outbound did after the merge of 2026-11-11, and left out
        // its IPv6 address and DNS servers while the VPN routed IPv6.
        val sources = File("src/main/java")
        assertTrue(sources.isDirectory, "${sources.absolutePath} is where the unit tests run from")
        val read = Regex("""\b(?:decodeSettingsBool|rememberMmkvBool)\(\s*(?:AppConfig\.)?PREF_IPV6_ENABLED\b([^)]*)\)""")
        val reads = sources.walk().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            read.findAll(file.readText()).map { "${file.name}: ${it.value}" to it.groupValues[1] }
        }.toList()
        assertTrue(reads.isNotEmpty(), "no read of Enable IPv6 found under ${sources.absolutePath}")
        assertEquals(emptyList<String>(), reads.filterNot { "DEFAULT_IPV6_ENABLED" in it.second }.map { it.first })
    }

    @Test
    fun test_isIpAddress() {
        assertFalse(Utils.isIpAddress("114.113.112.266"))
        assertFalse(Utils.isIpAddress("666.666.666.666"))
        assertFalse(Utils.isIpAddress("256.0.0.0"))
        assertFalse(Utils.isIpAddress("::ffff:127.0.0.0.1"))
        assertFalse(Utils.isIpAddress("baidu.com"))
        assertFalse(Utils.isIpAddress(""))

        assertTrue(Utils.isIpAddress("127.0.0.1"))
        assertTrue(Utils.isIpAddress("127.0.0.1:80"))
        assertTrue(Utils.isIpAddress("0.0.0.0/0"))
        assertTrue(Utils.isIpAddress("::1"))
        assertTrue(Utils.isIpAddress("[::1]:80"))
        assertTrue(Utils.isIpAddress("2605:2700:0:3::4713:93e3"))
        assertTrue(Utils.isIpAddress("[2605:2700:0:3::4713:93e3]:80"))
        assertTrue(Utils.isIpAddress("::ffff:192.168.173.22"))
        assertTrue(Utils.isIpAddress("[::ffff:192.168.173.22]:80"))
        assertTrue(Utils.isIpAddress("1::"))
        assertTrue(Utils.isIpAddress("::"))
        assertTrue(Utils.isIpAddress("::/0"))
        assertTrue(Utils.isIpAddress("10.24.56.0/24"))
        assertTrue(Utils.isIpAddress("2001:4321::1"))
        assertTrue(Utils.isIpAddress("240e:1234:abcd:12::6666"))
        assertTrue(Utils.isIpAddress("240e:1234:abcd:12::/64"))
    }

    @Test
    fun test_IsIpInCidr() {
        assertTrue(Utils.isIpInCidr("192.168.1.1", "192.168.1.0/24"))
        assertTrue(Utils.isIpInCidr("192.168.1.254", "192.168.1.0/24"))
        assertFalse(Utils.isIpInCidr("192.168.2.1", "192.168.1.0/24"))

        assertTrue(Utils.isIpInCidr("10.0.0.0", "10.0.0.0/8"))
        assertTrue(Utils.isIpInCidr("10.255.255.255", "10.0.0.0/8"))
        assertFalse(Utils.isIpInCidr("11.0.0.0", "10.0.0.0/8"))

        assertFalse(Utils.isIpInCidr("invalid-ip", "192.168.1.0/24"))
        assertFalse(Utils.isIpInCidr("192.168.1.1", "invalid-cidr"))
    }

}