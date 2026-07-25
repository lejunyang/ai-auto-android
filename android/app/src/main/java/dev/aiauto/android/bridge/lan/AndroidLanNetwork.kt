package dev.aiauto.android.bridge.lan

/**
 * 功能用途：发现合格 Android LAN 网络并连接用户选择的 Network，防止出站 socket
 * 使用默认网络、VPN 或 DNS 猜测目标。
 */

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.channels.SocketChannel
import java.time.Instant

class AndroidLanNetworkDirectory internal constructor(
    private val connectivityManager: ConnectivityManager,
) {
    fun interfaces(): List<LanLocalInterface> =
        connectivityManager.allNetworks.mapNotNull(::describe)
            .distinctBy(LanLocalInterface::id)
            .sortedWith(compareBy(LanLocalInterface::kind, LanLocalInterface::name))

    fun current(expected: LanLocalInterface): LanLocalInterface? =
        resolve(expected)?.let { expected }

    internal fun bind(
        socket: Socket,
        expected: LanLocalInterface,
    ) {
        val network = resolve(expected)
            ?: throw LanProtocolException("LAN_INTERFACE_MISMATCH")
        try {
            network.bindSocket(socket)
        } catch (error: Exception) {
            throw LanProtocolException(
                "LAN_INTERFACE_MISMATCH",
                "selected Android network cannot bind the socket",
                error,
            )
        }
        if (resolve(expected) == null) {
            throw LanProtocolException("LAN_INTERFACE_MISMATCH")
        }
    }

    private fun resolve(expected: LanLocalInterface): Network? =
        connectivityManager.allNetworks.singleOrNull { network ->
            describe(network) == expected
        }

    private fun describe(network: Network): LanLocalInterface? {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return null
        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> return null
        }
        val links = connectivityManager.getLinkProperties(network) ?: return null
        val interfaceName = links.interfaceName
            ?.takeIf { INTERFACE_NAME.matches(it) }
            ?: return null
        if (links.linkAddresses.none { address -> isPrivateLanAddress(address.address) }) {
            return null
        }
        return LanLocalInterface(
            id = "android-network-${network.networkHandle}-$interfaceName",
            name = interfaceName,
            kind = kind,
        )
    }

    companion object {
        fun from(context: Context): AndroidLanNetworkDirectory {
            val manager = requireNotNull(
                context.applicationContext.getSystemService(ConnectivityManager::class.java),
            )
            return AndroidLanNetworkDirectory(manager)
        }

        private val INTERFACE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")

        private fun isPrivateLanAddress(address: InetAddress): Boolean =
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isMulticastAddress &&
                (
                    address.isSiteLocalAddress ||
                        address.isLinkLocalAddress ||
                        isIpv6UniqueLocal(address)
                    )

        private fun isIpv6UniqueLocal(address: InetAddress): Boolean =
            address.address.size == 16 &&
                (address.address[0].toInt() and 0xfe) == 0xfc
    }
}

class AndroidLanSocketConnector(
    private val directory: AndroidLanNetworkDirectory,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 15_000,
) : LanSocketConnector {
    @Volatile
    private var activePort: NdjsonLanSocketPort? = null

    @Volatile
    private var activeChannel: SocketChannel? = null

    override fun connect(
        endpoint: LanAddressCandidate,
        localInterface: LanLocalInterface,
    ): LanSocketPort {
        val channel = SocketChannel.open()
        synchronized(this) {
            if (activePort != null || activeChannel != null) {
                channel.close()
                throw LanProtocolException("LAN_CONNECTION_FAILED")
            }
            activeChannel = channel
        }
        try {
            channel.configureBlocking(true)
            val socket = channel.socket()
            socket.tcpNoDelay = true
            socket.soTimeout = readTimeoutMillis
            directory.bind(socket, localInterface)
            socket.connect(
                InetSocketAddress(literalAddress(endpoint, localInterface), endpoint.port),
                connectTimeoutMillis,
            )
            if (directory.current(localInterface) == null) {
                throw LanProtocolException("LAN_INTERFACE_MISMATCH")
            }
            val port = NdjsonLanSocketPort(channel)
            synchronized(this) {
                if (activeChannel !== channel) {
                    port.close()
                    throw LanProtocolException("LAN_CONNECTION_CLOSED")
                }
                activeChannel = null
                activePort = port
            }
            return port
        } catch (error: LanProtocolException) {
            synchronized(this) {
                if (activeChannel === channel) activeChannel = null
            }
            runCatching { channel.close() }
            throw error
        } catch (error: Exception) {
            synchronized(this) {
                if (activeChannel === channel) activeChannel = null
            }
            runCatching { channel.close() }
            throw LanProtocolException(
                "LAN_ADDRESS_UNREACHABLE",
                "selected LAN address is unreachable",
                error,
            )
        }
    }

    @Synchronized
    override fun close() {
        activeChannel?.close()
        activeChannel = null
        activePort?.close()
        activePort = null
    }

    private fun literalAddress(
        endpoint: LanAddressCandidate,
        localInterface: LanLocalInterface,
    ): InetAddress {
        val parsed = try {
            InetAddresses.parseNumericAddress(endpoint.host)
        } catch (error: Exception) {
            throw LanProtocolException("LAN_ADDRESS_INVALID", cause = error)
        }
        if (
            endpoint.family == "ipv4" && parsed.address.size != 4 ||
            endpoint.family == "ipv6" && parsed.address.size != 16
        ) {
            throw LanProtocolException("LAN_ADDRESS_INVALID")
        }
        if (parsed is Inet6Address && parsed.isLinkLocalAddress) {
            val networkInterface = NetworkInterface.getByName(localInterface.name)
                ?: throw LanProtocolException("LAN_INTERFACE_MISMATCH")
            return Inet6Address.getByAddress(null, parsed.address, networkInterface)
        }
        return parsed
    }
}

class AndroidLanPairingSessionLauncher(
    private val context: Context,
    private val directory: AndroidLanNetworkDirectory,
    private val clock: LanClock = LanClock { Instant.now() },
) : AutoCloseable {
    @Volatile
    private var activeConnector: AndroidLanSocketConnector? = null

    fun connect(request: LanConnectRequest): LanOutboundSession {
        val selected = request.selectedLocalInterface
            ?: throw LanProtocolException("LAN_USER_CONFIRMATION_REQUIRED")
        val connector = synchronized(this) {
            activeConnector?.close()
            AndroidLanSocketConnector(directory).also {
                activeConnector = it
            }
        }
        return try {
            LanOutboundCoordinator(
                socketConnector = connector,
                replayStore = FileLanReplayStore(
                    context.noBackupFilesDir.resolve("lan/replay-state.json"),
                ),
                networkIdentity = LanNetworkIdentity { directory.current(selected) },
                clock = clock,
            ).connect(request)
        } catch (error: Exception) {
            synchronized(this) {
                if (activeConnector === connector) activeConnector = null
            }
            connector.close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        activeConnector?.close()
        activeConnector = null
    }
}
