package kumahon.desktop

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Finds kumapie on the LAN: it answers other devices on TCP 47823 but refuses non-LAN addresses (Tailscale 100.x), so only site-local /24s are scanned. */
object HubScan {
    const val PORT = 47823

    /** This PC's site-local IPv4 addresses (10/8, 172.16/12, 192.168/16). */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())

    /** Every host of each address's /24 except this PC's own addresses, once. ponytail: /24 only, a wider LAN needs the address typed in. */
    fun candidates(addresses: List<String>): List<String> = addresses.distinct().flatMap { a ->
        val p = a.substringBeforeLast('.')
        (1..254).map { "$p.$it" }
    }.distinct().filter { it !in addresses }

    /** The candidates with [PORT] open, as "ip:port". */
    fun find(addresses: List<String> = localAddresses(), timeoutMs: Int = 600, connect: (String, Int, Int) -> Boolean = ::open): List<String> {
        val pool = Executors.newFixedThreadPool(64) { r -> Thread(r).apply { isDaemon = true } }
        try {
            return candidates(addresses).map { ip -> pool.submit(Callable { ip.takeIf { connect(it, PORT, timeoutMs) } }) }
                .mapNotNull { it.get() }.map { "$it:$PORT" }
        } finally { pool.shutdownNow() }
    }

    private fun open(host: String, port: Int, timeoutMs: Int): Boolean =
        try { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } } catch (_: Exception) { false }
}
