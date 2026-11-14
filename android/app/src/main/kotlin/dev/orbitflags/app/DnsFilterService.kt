package dev.orbitflags.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dev.orbitflags.core.DnsPacket
import dev.orbitflags.core.Domains
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * On-device DNS filter. Only DNS traffic is routed into this local "VPN":
 * lookups for blocked domains get a "no such domain" answer, and everything
 * else is forwarded unchanged to the network's own resolver. No traffic
 * leaves the device through a tunnel and there is no remote server.
 */
class DnsFilterService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    @Volatile private var block: List<String> = emptyList()
    @Volatile private var allow: List<String> = emptyList()
    @Volatile private var upstream: Pair<Network, InetAddress>? = null
    @Volatile private var upstreamAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        // Sites the user reported are blocked along with the block list.
        block = Prefs.list(this, "block") + ReportStore.reportedDomains(this)
        allow = Prefs.list(this, "allow")
        if (tun == null && !start()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun start(): Boolean {
        val fd = try {
            Builder()
                .setSession("Orbit Content Flags")
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .addRoute(DNS_ADDRESS, 32) // only DNS packets enter the tunnel
                .setMtu(MTU)
                .setMetered(false)
                .setBlocking(true)
                .establish()
        } catch (e: IllegalStateException) {
            null
        } ?: return false // VPN permission was not granted or was revoked
        tun = fd
        pool = Executors.newFixedThreadPool(8)
        running = true
        reader = Thread({ readLoop(fd) }, "ocf-dns").also { it.start() }
        return true
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(MTU)
        while (running) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                break
            }
            if (n < 0) break
            val query = DnsPacket.parseQuery(buffer, n) ?: continue
            if (Domains.isBlocked(query.name, block, allow)) {
                write(output, DnsPacket.wrapResponse(query, DnsPacket.nxdomain(query)))
            } else {
                try {
                    pool?.execute { forward(query, output) }
                } catch (e: RejectedExecutionException) {
                    break
                }
            }
        }
    }

    private fun forward(query: DnsPacket.Query, output: FileOutputStream) {
        val (network, server) = resolver() ?: return
        try {
            DatagramSocket().use { socket ->
                protect(socket)
                network.bindSocket(socket)
                socket.soTimeout = 5000
                socket.send(DatagramPacket(query.dns, query.dns.size, server, 53))
                val answer = DatagramPacket(ByteArray(MTU - 28), MTU - 28)
                socket.receive(answer)
                write(output, DnsPacket.wrapResponse(query, answer.data.copyOf(answer.length)))
            }
        } catch (e: IOException) {
            // No answer: the app that asked will retry.
        }
    }

    private fun write(output: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(output) { output.write(packet) }
        } catch (e: IOException) {
            // Tunnel closed while answering.
        }
    }

    /** The DNS server of the real (non-VPN) network, refreshed every few seconds. */
    private fun resolver(): Pair<Network, InetAddress>? {
        val now = System.currentTimeMillis()
        val cached = upstream
        if (cached != null && now - upstreamAt < 10_000) return cached
        val cm = getSystemService(ConnectivityManager::class.java)
        var found: Pair<Network, InetAddress>? = null
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val server = cm.getLinkProperties(network)?.dnsServers?.firstOrNull() ?: continue
            found = network to server
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) break
        }
        upstream = found
        upstreamAt = now
        return found
    }

    private fun shutdown() {
        running = false
        pool?.shutdownNow()
        pool = null
        try {
            tun?.close()
        } catch (e: IOException) {
            // Already closed.
        }
        tun = null
        reader = null
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.orbitflags.app.STOP_DNS"
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private const val MTU = 9000

        @Volatile
        var running = false
            private set
    }
}
