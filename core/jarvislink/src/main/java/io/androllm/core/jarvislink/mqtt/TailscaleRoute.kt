package io.androllm.core.jarvislink.mqtt

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The link carries the broker password and tasks in plaintext MQTT, so it is
 * only allowed over Tailscale (WireGuard-encrypted, authenticated peers):
 * the host must be a Tailscale address (100.64.0.0/10, fd7a:115c:a1e0::/48)
 * or a MagicDNS name (`*.ts.net`) that resolves to one, AND a VPN network must
 * be up on the phone. A LAN IP or public host is refused — no CONNECT is sent.
 */
object TailscaleRoute {

    enum class HostKind { TAILSCALE_IP, MAGICDNS_NAME, NOT_TAILSCALE }

    fun classify(host: String): HostKind {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return HostKind.NOT_TAILSCALE
        parseIpv4(h)?.let { return if (isTailscaleV4(it)) HostKind.TAILSCALE_IP else HostKind.NOT_TAILSCALE }
        if (':' in h) {
            return if (h.startsWith("fd7a:115c:a1e0:")) HostKind.TAILSCALE_IP else HostKind.NOT_TAILSCALE
        }
        return if (h.endsWith(".ts.net") && h.length > ".ts.net".length) HostKind.MAGICDNS_NAME else HostKind.NOT_TAILSCALE
    }

    fun isTailscaleAddress(addr: InetAddress): Boolean = when (addr) {
        is Inet4Address -> isTailscaleV4(addr.address)
        is Inet6Address -> addr.address.let {
            it.size == 16 && it[0] == 0xfd.toByte() && it[1] == 0x7a.toByte() && it[2] == 0x11.toByte() &&
                it[3] == 0x5c.toByte() && it[4] == 0xa1.toByte() && it[5] == 0xe0.toByte()
        }
        else -> false
    }

    /** 100.64.0.0/10 = first octet 100, second octet 64..127. */
    private fun isTailscaleV4(b: ByteArray): Boolean =
        b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 0x40

    private fun parseIpv4(h: String): ByteArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }
}

/** Result of the pre-connect route check. */
sealed interface RouteDecision {
    /** Connect to [host] (an IP literal already verified to be a Tailscale address). */
    data class Go(val host: String) : RouteDecision
    data class Wait(val reason: String) : RouteDecision
    data class Refuse(val reason: String) : RouteDecision
}
