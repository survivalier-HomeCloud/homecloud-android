package fr.survivalier.homecloud

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * Détection automatique : broadcast UDP « HOMECLOUD_DISCOVER » sur le port 8651,
 * réponse « HOMECLOUD_HERE|port|nom|version » (même protocole que le client de bureau).
 */
object Discovery {
    private const val PORT = 8651
    private const val DURATION_MS = 2500L

    suspend fun scan(context: Context, onFound: (Server) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = runCatching {
            wifi?.createMulticastLock("homecloud")?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 200
                val probe = "HOMECLOUD_DISCOVER".toByteArray()
                val targets = broadcastTargets()
                val seen = HashSet<String>()
                val sendAt = longArrayOf(0, 600, 1500)
                var sent = 0
                val buf = ByteArray(512)
                val start = System.currentTimeMillis()
                while (isActive && System.currentTimeMillis() - start < DURATION_MS) {
                    val elapsed = System.currentTimeMillis() - start
                    if (sent < sendAt.size && elapsed >= sendAt[sent]) {
                        sent++
                        for (t in targets) {
                            runCatching { socket.send(DatagramPacket(probe, probe.size, t, PORT)) }
                        }
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val parts = String(packet.data, 0, packet.length).split('|')
                    if (parts.size < 3 || parts[0] != "HOMECLOUD_HERE") continue
                    val port = parts[1].toIntOrNull() ?: continue
                    val host = packet.address?.hostAddress ?: continue
                    if (seen.add("$host:$port")) onFound(Server(host, port, false, parts[2]))
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (ni in Collections.list(ifaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
            }
        }
        return out.toList()
    }
}
