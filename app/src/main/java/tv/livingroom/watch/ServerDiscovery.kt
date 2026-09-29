package tv.livingroom.watch

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/** A Jellyfin server that answered on the local network. */
data class FoundServer(val id: String, val name: String, val address: String)

/**
 * Finds Jellyfin servers on the home network the same way the official apps do: a UDP broadcast of
 * "Who is JellyfinServer?" to port 7359, which every Jellyfin server answers with its name and address.
 */
object ServerDiscovery {
    private const val PORT = 7359
    private const val MESSAGE = "Who is JellyfinServer?"
    private const val LISTEN_MS = 3_000L

    /** Blocks for a few seconds while servers answer. Call off the main thread. */
    fun find(context: Context): List<FoundServer> {
        // Some TV boxes drop broadcast replies to save power unless an app asks for them.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching { wifi?.createMulticastLock("jellyfin-discovery")?.apply { acquire() } }.getOrNull()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val payload = MESSAGE.toByteArray()
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, target, PORT)) }
                }
                val found = linkedMapOf<String, FoundServer>()
                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + LISTEN_MS
                while (true) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    socket.soTimeout = left.toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    parse(String(packet.data, 0, packet.length))?.let { found.putIfAbsent(it.id, it) }
                }
                return found.values.sortedBy { it.name.lowercase() }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    /**
     * Addresses to try for what the user typed. "192.168.1.10" becomes http://192.168.1.10:8096 first
     * (Jellyfin's usual port), then https:// and plain http:// for servers behind a proxy or domain name.
     */
    fun candidates(typed: String): List<String> {
        val text = typed.trim().trimEnd('/')
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return listOf(text)
        val host = text.substringBefore('/')
        val path = text.removePrefix(host)
        if (Regex(":\\d+$").containsMatchIn(host)) return listOf("http://$text", "https://$text")
        return listOf("http://$host:8096$path", "https://$text", "http://$text")
    }

    private fun parse(text: String): FoundServer? = runCatching {
        val json = JSONObject(text)
        val address = json.optString("Address").trim().trimEnd('/')
        if (address.isBlank()) return null
        FoundServer(
            id = json.optString("Id").ifBlank { address },
            name = json.optString("Name").ifBlank { "Jellyfin" },
            address = address
        )
    }.getOrNull()

    /** The general broadcast address plus each network's own, since some routers only pass the latter. */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (network in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp || network.isLoopback) continue
                network.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
            }
        }
        return addresses.distinct()
    }
}
