package com.v2ray.ang.fmt

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.v2ray.ang.dto.VmessQRCode
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import java.util.Base64 as JavaBase64

/**
 * Unit tests for VmessFmt, covering the dialMode member of the QR-code JSON.
 */
class VmessFmtTest {

    private lateinit var mockBase64: MockedStatic<Base64>
    private lateinit var mockLog: MockedStatic<Log>
    private lateinit var mockTextUtils: MockedStatic<TextUtils>

    @BeforeEach
    fun setUp() {
        mockLog = mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)

        mockTextUtils = mockStatic(TextUtils::class.java)
        mockTextUtils.`when`<Boolean> {
            TextUtils.isEmpty(Mockito.any(CharSequence::class.java))
        }.thenAnswer { invocation ->
            (invocation.arguments[0] as CharSequence?).isNullOrEmpty()
        }

        mockBase64 = mockStatic(Base64::class.java)
        mockBase64.`when`<ByteArray> {
            Base64.decode(Mockito.anyString(), Mockito.anyInt())
        }.thenAnswer { invocation ->
            val input = invocation.arguments[0] as String
            val flags = invocation.arguments[1] as Int
            val decoder = if ((flags and Base64.URL_SAFE) != 0) {
                JavaBase64.getUrlDecoder()
            } else {
                JavaBase64.getDecoder()
            }
            decoder.decode(input)
        }
        mockBase64.`when`<String> {
            Base64.encodeToString(Mockito.any(ByteArray::class.java), Mockito.anyInt())
        }.thenAnswer { invocation ->
            val input = invocation.arguments[0] as ByteArray
            val flags = invocation.arguments[1] as Int
            var encoder = if ((flags and Base64.URL_SAFE) != 0) {
                JavaBase64.getUrlEncoder()
            } else {
                JavaBase64.getEncoder()
            }
            if ((flags and Base64.NO_PADDING) != 0) {
                encoder = encoder.withoutPadding()
            }
            encoder.encodeToString(input)
        }
    }

    @AfterEach
    fun tearDown() {
        mockBase64.close()
        mockTextUtils.close()
        mockLog.close()
    }

    private fun createConfig(mode: String?): ProfileItem =
        ProfileItem.create(EConfigType.VMESS).apply {
            remarks = "Dial Mode"
            server = "example.com"
            serverPort = "443"
            password = "uuid"
            method = "auto"
            network = "tcp"
            headerType = "none"
            dialMode = mode
        }

    private fun encodeQrCode(json: String): String =
        "vmess://" + JavaBase64.getEncoder().encodeToString(json.toByteArray())

    @Test
    fun test_toUri_writesDialModeIntoQrCodeJson() {
        val uri = VmessFmt.toUri(createConfig("code-1"))

        val json = String(JavaBase64.getDecoder().decode(uri))
        val vmessQRCode = JsonUtil.fromJson(json, VmessQRCode::class.java)
        assertNotNull(vmessQRCode)
        assertEquals("code-1", vmessQRCode?.dialMode)
    }

    @Test
    fun test_parse_readsDialModeFromQrCodeJson() {
        val json = """{"v":"2","ps":"Dial Mode","add":"example.com","port":"443","id":"uuid","aid":"0","scy":"auto","net":"tcp","type":"none","host":"","path":"","tls":"","sni":"","alpn":"","fp":"","dialMode":"code-1"}"""

        val result = VmessFmt.parse(encodeQrCode(json))

        assertNotNull(result)
        assertEquals("code-1", result?.dialMode)
        assertEquals("example.com", result?.server)
    }

    @Test
    fun test_parse_leavesDialModeEmptyWhenAbsent() {
        val json = """{"v":"2","ps":"Plain","add":"example.com","port":"443","id":"uuid","aid":"0","scy":"auto","net":"tcp","type":"none","host":"","path":"","tls":""}"""

        val result = VmessFmt.parse(encodeQrCode(json))

        assertNotNull(result)
        assertTrue(result?.dialMode.isNullOrEmpty())
    }

    @Test
    fun test_parseAndToUri_roundTripPreservesDialMode() {
        val regenerated = VmessFmt.toUri(createConfig("code-1"))

        val reparsed = VmessFmt.parse("vmess://$regenerated")

        assertNotNull(reparsed)
        assertEquals("code-1", reparsed?.dialMode)
    }

    // PattNG: tcpUserTimeout, the "tut" member of the QR-code JSON, as PattN writes it: the number where it counts
    // (TcpUserTimeoutRule), blank otherwise.
    @Test
    fun test_toUriAndParse_carryTcpUserTimeoutInQrCodeJsonWhereItCounts() {
        val config = createConfig("code-1").apply { tcpUserTimeout = 10000 }

        val json = String(JavaBase64.getDecoder().decode(VmessFmt.toUri(config)))
        assertEquals("10000", JsonUtil.fromJson(json, VmessQRCode::class.java)?.tut)
        assertEquals(10000, VmessFmt.parse("vmess://${VmessFmt.toUri(config)}")?.tcpUserTimeout)

        // Over mKCP the connection is UDP: the member stays blank.
        config.network = "kcp"
        val kcpJson = String(JavaBase64.getDecoder().decode(VmessFmt.toUri(config)))
        assertEquals("", JsonUtil.fromJson(kcpJson, VmessQRCode::class.java)?.tut)
    }

    @Test
    fun test_parse_readsTcpUserTimeoutFromQrCodeJsonAsAWholeNumberAboveZero() {
        fun parsed(value: String) = VmessFmt.parse(
            encodeQrCode(
                """{"v":"2","ps":"Timeout","add":"example.com","port":"443","id":"uuid","aid":"0","scy":"auto","net":"tcp","type":"none","host":"","path":"","tls":"","tut":"$value"}"""
            )
        )

        assertEquals(10000, parsed("10000")?.tcpUserTimeout)
        for (none in listOf("", "0", "-1", "2s")) {
            assertNotNull(parsed(none))
            assertNull(parsed(none)?.tcpUserTimeout, none)
        }
    }

    // PattNG: the QR-code JSON carries them as its own "cp" and "esr" members, after "cs".
    @Test
    fun test_toUriAndParse_carryCurvePreferencesAndSessionResumptionInQrCodeJson() {
        val config = createConfig(null).apply {
            security = "tls"
            fingerPrint = "unsafe"
            curvePreferences = "X25519"
            enableSessionResumption = true
        }

        val regenerated = VmessFmt.toUri(config)
        val json = String(JavaBase64.getDecoder().decode(regenerated))
        val vmessQRCode = JsonUtil.fromJson(json, VmessQRCode::class.java)
        assertEquals("X25519", vmessQRCode?.cp)
        assertEquals("1", vmessQRCode?.esr)
        assertTrue(json.indexOf("\"cs\"") < json.indexOf("\"cp\"") && json.indexOf("\"cp\"") < json.indexOf("\"esr\""), json)

        val reparsed = VmessFmt.parse("vmess://$regenerated")
        assertEquals("X25519", reparsed?.curvePreferences)
        assertEquals(true, reparsed?.enableSessionResumption)

        config.enableSessionResumption = false
        val off = VmessFmt.parse("vmess://${VmessFmt.toUri(config)}")
        assertEquals(false, off?.enableSessionResumption)
    }
}
