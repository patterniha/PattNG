package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType

/**
 * PattNG: where the tcpUserTimeout of a profile counts (PattN Common/TcpUserTimeoutRule.cs). Like the dialMode it goes
 * to the sockopt of the outbound, in milliseconds, and Xray sets it on TCP sockets alone (TCP_USER_TIMEOUT; the fork
 * adds Windows TCP_MAXRTMS and macOS TCP_RXT_CONNDROPTIME), so the editors show it, a configuration writes it and a
 * link carries it only where the connection of the outbound is TCP. Elsewhere a value the profile holds is kept, unused.
 * An object of its own, as [TlsSettingsCheck] is, since the outbound builder, the links and the editors all go by it.
 */
object TcpUserTimeoutRule {

    /**
     * Whether the outbound built for a profile of [configType], with [network], [security] and [alpn] as the profile or
     * its editor holds them, connects over TCP: never for WireGuard and Hysteria2, which run over UDP; always for SOCKS
     * and HTTP, whose outbound takes no transport of the profile's; MASQUE only over HTTP/2, as
     * [CoreOutboundBuilder.toOutboundMasque] runs it, with the alpn h2; VMess, VLESS, Shadowsocks and Trojan unless over
     * mKCP, or over XHTTP with TLS and the alpn h3 alone, which the fork then dials over QUIC (its splithttp dialer;
     * REALITY keeps it on HTTP/2). An Aether profile goes by its core instead, see [AetherCore.connectsOverTcp].
     */
    fun applies(configType: EConfigType, network: String?, security: String?, alpn: String?): Boolean = when (configType) {
        EConfigType.SOCKS, EConfigType.HTTP -> true
        EConfigType.MASQUE -> alpn?.trim() == "h2"
        EConfigType.VMESS, EConfigType.VLESS, EConfigType.SHADOWSOCKS, EConfigType.TROJAN ->
            when (NetworkType.fromString(network?.trim())) {
                NetworkType.KCP -> false
                NetworkType.XHTTP -> !(security == AppConfig.TLS && CoreOutboundBuilder.alpnProtocols(alpn) == listOf("h3"))
                else -> true
            }

        else -> false
    }

    /**
     * Whether the tcpUserTimeout of [profile] counts, see [applies]: for an Aether profile where its core connects out
     * over TCP, through the exit-node, see [AetherCore.connectsOverTcp].
     */
    fun appliesTo(profile: ProfileItem): Boolean =
        if (profile.configType == EConfigType.AETHER) {
            AetherCore.connectsOverTcp(profile)
        } else {
            applies(profile.configType, profile.network, profile.security, profile.alpn)
        }

    /**
     * The tcpUserTimeout of [profile] as its outbound, or the freedom exit-node of its Aether core, takes it: a whole
     * number of milliseconds above 0 where it counts ([appliesTo]), else null.
     */
    fun valueOf(profile: ProfileItem): Int? = profile.tcpUserTimeout?.takeIf { it > 0 && appliesTo(profile) }

    /** A tcpUserTimeout as a link or the VMess JSON writes it, or a field holds it: a whole number above 0, else null. */
    fun parse(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}
